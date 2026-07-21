package haitai.safemask.domain.chatmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.airun.entity.AiRun;
import haitai.safemask.domain.airun.gateway.AiGateway;
import haitai.safemask.domain.airun.gateway.AiGatewayResponse;
import haitai.safemask.domain.airun.gateway.AiProgressListener;
import haitai.safemask.domain.airun.gateway.AiPromptMessage;
import haitai.safemask.domain.airun.repository.AiRunRepository;
import haitai.safemask.domain.chatmessage.approval.IssuedMaskingApproval;
import haitai.safemask.domain.chatmessage.approval.MaskingApprovalService;
import haitai.safemask.domain.chatmessage.approval.MaskingApprovalSnapshot;
import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.fileasset.service.FileAssetService;
import haitai.safemask.domain.fileasset.service.GeneratedFileService;
import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingentity.repository.MaskingEntityRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ChatMessageServiceApprovalTest {

	@Mock private ChatRoomRepository chatRoomRepository;
	@Mock private ChatMessageRepository chatMessageRepository;
	@Mock private AiRunRepository aiRunRepository;
	@Mock private MaskingEntityRepository maskingEntityRepository;
	@Mock private MaskingService maskingService;
	@Mock private MaskingApprovalService maskingApprovalService;
	@Mock private AttachmentTextExtractor attachmentTextExtractor;
	@Mock private GeneratedFileService generatedFileService;
	@Mock private FileAssetService fileAssetService;
	@Mock private AiGateway aiGateway;
	@Mock private PlatformTransactionManager transactionManager;

	private ChatMessageService service;
	private Member member;
	private ChatRoom chatRoom;

	@BeforeEach
	void setUp() {
		service = new ChatMessageService(chatRoomRepository, chatMessageRepository, aiRunRepository,
			maskingEntityRepository, maskingService, maskingApprovalService, attachmentTextExtractor,
			generatedFileService, fileAssetService, aiGateway, transactionManager, "test-model");
		member = Member.create("employee", "encoded", "사용자", "employee@example.com", "개발");
		ReflectionTestUtils.setField(member, "id", 7L);
		chatRoom = ChatRoom.create(member, "대화");
		ReflectionTestUtils.setField(chatRoom, "id", 31L);
	}

	@Test
	@DisplayName("민감정보 탐지 0건이면 미리보기 없이 곧바로 확정 요청을 만든다")
	void zeroDetectionSkipsPreview() {
		stubActiveRoom();
		stubApprovedWriteIds();
		when(maskingService.mask(31L, "안녕하세요")).thenReturn(
			new MaskingResult("안녕하세요", "안녕하세요", List.of()));

		ChatMessageService.PreparedSend prepared = service.prepare(member,
			new ChatSendRequest(31L, "안녕하세요", null, List.of()));

		assertThat(prepared.previewRequired()).isFalse();
		assertThat(prepared.chatRoomId()).isEqualTo(31L);
		verify(maskingApprovalService, never()).issue(any(), any(), eq(false), any(), any(), any(), any());
		verify(chatMessageRepository).save(any(ChatMessage.class));
	}

	@Test
	@DisplayName("첨부 전체 추출과 검사에 성공해 탐지 0건이면 파일도 미리보기 없이 처리한다")
	void zeroDetectionFileSkipsPreview() {
		stubActiveRoom();
		stubApprovedWriteIds();
		MockMultipartFile file = textFile("guide.txt", "공개 안내");
		when(attachmentTextExtractor.extract(List.of(file))).thenReturn("\n\n[첨부: guide.txt]\n공개 안내");
		when(maskingService.mask(31L, "요약해 줘\n\n[첨부: guide.txt]\n공개 안내")).thenReturn(
			new MaskingResult("원문", "요약해 줘\n\n[첨부: guide.txt]\n공개 안내", List.of()));

		ChatMessageService.PreparedSend prepared = service.prepareWithFiles(member,
			new ChatSendRequest(31L, "요약해 줘", null, List.of()), List.of(file));

		assertThat(prepared.previewRequired()).isFalse();
		verify(fileAssetService).storeUploads(chatRoom, List.of(file));
		verify(maskingApprovalService, never()).issue(any(), any(), eq(false), any(), any(), any(), any());
	}

	@Test
	@DisplayName("첨부 추출 실패는 마스킹이나 AI 호출로 진행하지 않고 즉시 차단한다")
	void fileExtractionFailureStopsPipeline() {
		MockMultipartFile file = textFile("broken.txt", "내용");
		when(attachmentTextExtractor.extract(List.of(file)))
			.thenThrow(new CustomException(ErrorCode.INVALID_REQUEST, "첨부 파일에서 텍스트를 찾지 못했습니다."));

		assertThatThrownBy(() -> service.prepareWithFiles(member,
			new ChatSendRequest(31L, "요약", null, List.of()), List.of(file)))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("텍스트를 찾지 못했습니다");
		verify(maskingService, never()).mask(anyLong(), any());
		verify(aiGateway, never()).generate(any(), any());
	}

	@Test
	@DisplayName("민감정보가 탐지되면 AI 실행이나 원본 저장 없이 승인 ID가 있는 미리보기만 반환한다")
	void detectionIssuesPreviewOnly() {
		stubActiveRoom();
		stubTransaction();
		Detection detection = new Detection(MaskingType.PHONE, "010-1234-5678", "[PHONE_001]",
			3, 16, "전화번호");
		MaskingResult result = new MaskingResult("전화 010-1234-5678", "전화 [PHONE_001]", List.of(detection));
		MaskingApprovalSnapshot snapshot = new MaskingApprovalSnapshot(7L, 31L, false,
			"전화 010-1234-5678", result, List.of(), List.of());
		when(maskingService.mask(31L, result.originalText())).thenReturn(result);
		when(maskingApprovalService.issue(member, chatRoom, false, result.originalText(), result, List.of(), null))
			.thenReturn(new IssuedMaskingApproval("approval-id", snapshot));

		ChatMessageService.PreparedSend prepared = service.prepare(member,
			new ChatSendRequest(31L, result.originalText(), null, List.of()));

		assertThat(prepared.previewRequired()).isTrue();
		assertThat(prepared.previewResponse().approvalId()).isEqualTo("approval-id");
		assertThat(prepared.previewResponse().maskedPreview()).isEqualTo("전화 [PHONE_001]");
		verify(chatMessageRepository, never()).save(any());
		verify(aiRunRepository, never()).save(any());
		verify(aiGateway, never()).generate(any(), any());
	}

	@Test
	@DisplayName("승인 전송은 클라이언트가 다시 보낸 원문 대신 서버 스냅샷의 마스킹본을 저장한다")
	void approvalUsesServerSnapshot() {
		stubActiveRoom();
		stubApprovedWriteIds();
		MaskingResult approvedResult = new MaskingResult("전화 010-1234-5678", "전화 [PHONE_001]", List.of());
		MaskingApprovalSnapshot snapshot = new MaskingApprovalSnapshot(7L, 31L, false,
			"전화 010-1234-5678", approvedResult, List.of(), List.of());
		when(maskingApprovalService.consume(member, "approval-id", null)).thenReturn(snapshot);

		service.prepare(member, new ChatSendRequest(31L, "변조된 클라이언트 원문", "approval-id", List.of()));

		ArgumentCaptor<ChatMessage> messageCaptor = ArgumentCaptor.forClass(ChatMessage.class);
		verify(chatMessageRepository).save(messageCaptor.capture());
		assertThat(messageCaptor.getValue().getOriginalContent()).isEqualTo("전화 010-1234-5678");
		assertThat(messageCaptor.getValue().getMaskedContent()).isEqualTo("전화 [PHONE_001]");
		verify(maskingService, never()).mask(anyLong(), any());
	}

	@Test
	@DisplayName("승인 스냅샷의 마스킹본만 실제 AI Gateway 사용자 입력으로 전달한다")
	void gatewayReceivesExactlyApprovedMaskedSnapshot() {
		stubActiveRoom();
		stubTransaction();
		MaskingResult approvedResult = new MaskingResult("전화 010-1234-5678", "전화 [PHONE_001]", List.of());
		MaskingApprovalSnapshot snapshot = new MaskingApprovalSnapshot(7L, 31L, false,
			"전화 010-1234-5678", approvedResult, List.of(), List.of());
		when(maskingApprovalService.consume(member, "approval-id", null)).thenReturn(snapshot);

		AtomicReference<ChatMessage> userMessageRef = new AtomicReference<>();
		AtomicReference<AiRun> aiRunRef = new AtomicReference<>();
		when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
			ChatMessage message = invocation.getArgument(0);
			long id = message.getRole().name().equals("USER") ? 101L : 102L;
			ReflectionTestUtils.setField(message, "id", id);
			if (id == 101L) {
				userMessageRef.set(message);
			}
			return message;
		});
		when(aiRunRepository.save(any(AiRun.class))).thenAnswer(invocation -> {
			AiRun aiRun = invocation.getArgument(0);
			ReflectionTestUtils.setField(aiRun, "id", 201L);
			aiRunRef.set(aiRun);
			return aiRun;
		});

		ChatMessageService.PreparedSend prepared = service.prepare(member,
			new ChatSendRequest(31L, "외부에서 변조한 원문", "approval-id", List.of()));
		when(aiRunRepository.findByIdForUpdate(201L)).thenAnswer(invocation -> Optional.of(aiRunRef.get()));
		when(chatRoomRepository.findById(31L)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findRecentByChatRoomIdOrderByIdDesc(31L, 20))
			.thenAnswer(invocation -> List.of(userMessageRef.get()));
		when(aiGateway.generate(any(), any())).thenReturn(
			new AiGatewayResponse("확인했습니다.", "test-model", 10, 4, List.of()));
		when(maskingService.restore(31L, "확인했습니다.")).thenReturn("확인했습니다.");
		when(chatMessageRepository.findById(101L)).thenAnswer(invocation -> Optional.of(userMessageRef.get()));
		when(generatedFileService.materialize(chatRoom, "확인했습니다."))
			.thenReturn(new GeneratedFileService.Outcome("확인했습니다.", List.of()));

		service.completePrepared(prepared, AiProgressListener.NONE);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<AiPromptMessage>> promptCaptor = ArgumentCaptor.forClass(List.class);
		verify(aiGateway).generate(promptCaptor.capture(), eq(AiProgressListener.NONE));
		assertThat(promptCaptor.getValue())
			.filteredOn(message -> message.role().equals("user"))
			.extracting(AiPromptMessage::content)
			.containsExactly("전화 [PHONE_001]");
		assertThat(promptCaptor.getValue())
			.extracting(AiPromptMessage::content)
			.doesNotContain("전화 010-1234-5678", "외부에서 변조한 원문");
	}

	private void stubActiveRoom() {
		when(chatRoomRepository.findByIdAndMember_IdAndStatus(31L, 7L, ChatRoomStatus.ACTIVE))
			.thenReturn(Optional.of(chatRoom));
	}

	private void stubApprovedWriteIds() {
		stubTransaction();
		when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
			ChatMessage message = invocation.getArgument(0);
			ReflectionTestUtils.setField(message, "id", 101L);
			return message;
		});
		when(aiRunRepository.save(any(AiRun.class))).thenAnswer(invocation -> {
			AiRun aiRun = invocation.getArgument(0);
			ReflectionTestUtils.setField(aiRun, "id", 201L);
			return aiRun;
		});
	}

	private void stubTransaction() {
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
			.thenAnswer(invocation -> new SimpleTransactionStatus());
	}

	private MockMultipartFile textFile(String name, String content) {
		return new MockMultipartFile("files", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
	}
}
