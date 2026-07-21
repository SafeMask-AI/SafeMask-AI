package haitai.safemask.domain.chatmessage.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.masking.config.MaskingProperties;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

@ExtendWith(MockitoExtension.class)
class MaskingApprovalServiceTest {

	@Mock
	private MaskingApprovalStore approvalStore;
	@Mock
	private FileFingerprintService fileFingerprintService;

	private MaskingProperties properties;
	private MaskingApprovalService service;
	private Member member;
	private ChatRoom chatRoom;

	@BeforeEach
	void setUp() {
		properties = new MaskingProperties();
		properties.setApprovalTtl(Duration.ofMinutes(12));
		service = new MaskingApprovalService(approvalStore, fileFingerprintService, properties);
		member = Member.create("employee", "encoded", "사용자", "employee@example.com", "개발");
		ReflectionTestUtils.setField(member, "id", 7L);
		chatRoom = ChatRoom.create(member, "대화");
		ReflectionTestUtils.setField(chatRoom, "id", 31L);
	}

	@Test
	@DisplayName("승인 발급 시 마스킹 결과와 첨부 지문을 서버 스냅샷으로 TTL 동안 보관한다")
	void issueStoresServerSnapshot() {
		MaskingResult result = new MaskingResult("010-1234-5678", "[PHONE_001]", List.of());
		List<MultipartFile> files = List.of(textFile("a.txt", "original"));
		List<ApprovedFileFingerprint> fingerprints = List.of(
			new ApprovedFileFingerprint("a.txt", 8, "hash"));
		List<ManualMaskRequest> manualMasks = List.of(new ManualMaskRequest("프로젝트명", MaskingType.CUSTOM));
		when(fileFingerprintService.fingerprint(files)).thenReturn(fingerprints);
		when(approvalStore.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
			.thenReturn("approval-id");

		IssuedMaskingApproval issued = service.issue(member, chatRoom, true, "질문\n[첨부: a.txt]", result,
			manualMasks, files);

		assertThat(issued.approvalId()).isEqualTo("approval-id");
		assertThat(issued.snapshot().memberId()).isEqualTo(7L);
		assertThat(issued.snapshot().chatRoomId()).isEqualTo(31L);
		assertThat(issued.snapshot().maskingResult()).isSameAs(result);
		assertThat(issued.snapshot().manualMasks()).isEqualTo(manualMasks);
		assertThat(issued.snapshot().files()).isEqualTo(fingerprints);
		verify(approvalStore).save(issued.snapshot(), Duration.ofMinutes(12));
	}

	@Test
	@DisplayName("승인 당시와 동일한 첨부만 서버 스냅샷을 1회 소비할 수 있다")
	void consumeWithMatchingFiles() {
		List<MultipartFile> files = List.of(textFile("a.txt", "same"));
		List<ApprovedFileFingerprint> fingerprints = List.of(
			new ApprovedFileFingerprint("a.txt", 4, "same-hash"));
		MaskingApprovalSnapshot snapshot = snapshot(fingerprints);
		when(approvalStore.find(7L, "approval-id")).thenReturn(Optional.of(snapshot));
		when(fileFingerprintService.fingerprint(files)).thenReturn(fingerprints);
		when(approvalStore.consume(7L, "approval-id")).thenReturn(Optional.of(snapshot));

		assertThat(service.consume(member, "approval-id", files)).isSameAs(snapshot);
		verify(approvalStore).consume(7L, "approval-id");
	}

	@Test
	@DisplayName("승인 뒤 첨부 바이트가 달라지면 승인 ID를 소비하지 않고 거절한다")
	void rejectChangedFilesBeforeConsumption() {
		List<ApprovedFileFingerprint> approved = List.of(
			new ApprovedFileFingerprint("a.txt", 4, "old-hash"));
		List<MultipartFile> changedFiles = List.of(textFile("a.txt", "changed"));
		when(approvalStore.find(7L, "approval-id")).thenReturn(Optional.of(snapshot(approved)));
		when(fileFingerprintService.fingerprint(changedFiles)).thenReturn(List.of(
			new ApprovedFileFingerprint("a.txt", 7, "new-hash")));

		assertThatThrownBy(() -> service.consume(member, "approval-id", changedFiles))
			.isInstanceOfSatisfying(CustomException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MASKING_APPROVAL_FILE_CHANGED));
		verify(approvalStore, never()).consume(7L, "approval-id");
	}

	@Test
	@DisplayName("만료되었거나 다른 사용자의 승인 ID는 일반 전송으로 우회하지 않고 거절한다")
	void rejectMissingApproval() {
		when(approvalStore.find(7L, "expired-id")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.consume(member, "expired-id", List.of()))
			.isInstanceOfSatisfying(CustomException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.MASKING_APPROVAL_INVALID));
		verify(fileFingerprintService, never()).fingerprint(org.mockito.ArgumentMatchers.anyList());
	}

	private MaskingApprovalSnapshot snapshot(List<ApprovedFileFingerprint> files) {
		return new MaskingApprovalSnapshot(7L, 31L, true, "표시 질문",
			new MaskingResult("원문", "[MASK_001]", List.of()), List.of(), files);
	}

	private MockMultipartFile textFile(String name, String content) {
		return new MockMultipartFile("files", name, "text/plain", content.getBytes());
	}
}
