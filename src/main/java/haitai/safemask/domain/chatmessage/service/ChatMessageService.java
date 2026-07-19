package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.domain.airun.entity.AiRun;
import haitai.safemask.domain.airun.gateway.AiGateway;
import haitai.safemask.domain.airun.gateway.AiGatewayResponse;
import haitai.safemask.domain.airun.gateway.AiProgressListener;
import haitai.safemask.domain.airun.gateway.AiPromptMessage;
import haitai.safemask.domain.airun.prompt.SafeMaskSystemPrompt;
import haitai.safemask.domain.airun.repository.AiRunRepository;
import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendResponse;
import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.chatmessage.dto.MaskingDetectionResponse;
import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatmessage.enums.MessageRole;
import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.fileasset.service.FileAssetService;
import haitai.safemask.domain.fileasset.service.GeneratedFileService;
import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.maskingentity.entity.MaskingEntity;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingentity.repository.MaskingEntityRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 채팅 메시지 저장, 마스킹, GPT 호출, 원복을 한 번에 관통시키는 서비스입니다.
 *
 * <p>보안상 GPT로 보내는 컨텍스트는 항상 ChatMessage.maskedContent만 사용합니다.
 * originalContent는 화면 표시와 사내 DB 보관용이며, 이 서비스 안에서도 외부 호출 메시지로
 * 조립하지 않습니다.
 */
@Service
@Transactional(readOnly = true)
public class ChatMessageService {

	private static final int CONTEXT_MESSAGE_LIMIT = 20;

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final AiRunRepository aiRunRepository;
	private final MaskingEntityRepository maskingEntityRepository;
	private final MaskingService maskingService;
	private final AttachmentTextExtractor attachmentTextExtractor;
	private final GeneratedFileService generatedFileService;
	private final FileAssetService fileAssetService;
	private final AiGateway aiGateway;
	private final String modelName;
	private final TransactionTemplate writeTransaction;

	public ChatMessageService(ChatRoomRepository chatRoomRepository,
		ChatMessageRepository chatMessageRepository,
		AiRunRepository aiRunRepository,
		MaskingEntityRepository maskingEntityRepository,
		MaskingService maskingService,
		AttachmentTextExtractor attachmentTextExtractor,
		GeneratedFileService generatedFileService,
		FileAssetService fileAssetService,
		AiGateway aiGateway,
		PlatformTransactionManager transactionManager,
		@Value("${safemask.ai.model:gpt-5.5}") String modelName) {
		this.chatRoomRepository = chatRoomRepository;
		this.chatMessageRepository = chatMessageRepository;
		this.aiRunRepository = aiRunRepository;
		this.maskingEntityRepository = maskingEntityRepository;
		this.maskingService = maskingService;
		this.attachmentTextExtractor = attachmentTextExtractor;
		this.generatedFileService = generatedFileService;
		this.fileAssetService = fileAssetService;
		this.aiGateway = aiGateway;
		this.modelName = modelName;
		this.writeTransaction = new TransactionTemplate(transactionManager);
	}

	private record InitialUserWrite(Long chatRoomId, Long userMessageId, Long aiRunId,
		Long previousAssistantMessageId) {
	}

	/**
	 * 마스킹 미리보기 또는 AI 호출 직전까지 확정된 요청입니다.
	 *
	 * <p>SSE 응답은 HTTP 연결을 반환하기 전에 첨부 추출·원본 저장까지 끝내야 임시 업로드
	 * 파일의 수명과 무관하게 안전하게 비동기 AI 호출을 시작할 수 있습니다.
	 */
	public record PreparedSend(
		ChatSendResponse previewResponse,
		Long chatRoomId,
		Long userMessageId,
		Long aiRunId,
		Map<MaskingType, Long> summary,
		List<MaskingDetectionResponse> detections
	) {
		public boolean previewRequired() {
			return previewResponse != null;
		}
	}

	/**
	 * 사용자의 채팅 입력을 처리합니다.
	 *
	 * <p>민감정보가 탐지됐고 사용자가 아직 승인하지 않았다면 GPT 호출 없이 미리보기만 반환합니다.
	 * 탐지 0건이거나 approved=true인 요청은 maskedContent 기준으로 이전 대화 맥락을 구성해
	 * GPT에 전송하고, 응답은 Redis 매핑으로 원복해 저장합니다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChatSendResponse send(Member member, ChatSendRequest request) {
		return completePrepared(prepare(member, request), AiProgressListener.NONE);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChatSendResponse sendWithFiles(Member member, ChatSendRequest request, List<MultipartFile> files) {
		return completePrepared(prepareWithFiles(member, request, files), AiProgressListener.NONE);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PreparedSend prepare(Member member, ChatSendRequest request) {
		return prepareInternal(member, request, request.content(), null);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public PreparedSend prepareWithFiles(Member member, ChatSendRequest request, List<MultipartFile> files) {
		String attachmentText = attachmentTextExtractor.extract(files);
		String baseContent = request.content() == null ? "" : request.content().trim();
		String processingContent = (baseContent + attachmentText).trim();
		String displayContent = buildDisplayContent(baseContent, files);
		return prepareInternal(member, new ChatSendRequest(request.chatRoomId(), processingContent, request.approved(),
			request.manualMasks()), displayContent, files);
	}

	private PreparedSend prepareInternal(Member member, ChatSendRequest request, String displayContent,
		List<MultipartFile> files) {
		validateRequest(request);
		boolean forcePreview = files != null && !files.isEmpty();
		boolean temporaryChatRoom = request.chatRoomId() == null;

		ChatRoom chatRoom = resolveChatRoom(member, request, displayContent);
		MaskingResult maskingResult = applyMasking(chatRoom.getId(), request);
		List<MaskingDetectionResponse> detections = maskingResult.detections()
			.stream()
			.map(MaskingDetectionResponse::from)
			.toList();

		if ((forcePreview || maskingResult.hasDetections()) && !request.isApproved()) {
			return new PreparedSend(
				ChatSendResponse.preview(chatRoom.getId(), temporaryChatRoom, maskingResult.maskedText(),
					maskingResult.summary(), detections),
				null, null, null, maskingResult.summary(), detections);
		}

		// 첨부 원본은 미리보기 단계에서는 저장하지 않고, 사용자가 전송을 확정한 시점에만 보관한다.
		// AI 호출은 이 쓰기 트랜잭션이 끝난 뒤 실행해 DB 커넥션을 오래 붙잡지 않는다.
		InitialUserWrite write = saveApprovedUserMessage(chatRoom.getId(), displayContent, maskingResult, files);
		return new PreparedSend(null, write.chatRoomId(), write.userMessageId(), write.aiRunId(),
			maskingResult.summary(), detections);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChatSendResponse completePrepared(PreparedSend prepared, AiProgressListener progressListener) {
		if (prepared.previewRequired()) {
			return prepared.previewResponse();
		}
		return generateAnswer(prepared.chatRoomId(), prepared.userMessageId(), prepared.aiRunId(),
			prepared.summary(), prepared.detections(), progressListener, null);
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void failPrepared(PreparedSend prepared, String message) {
		if (prepared != null && !prepared.previewRequired() && prepared.aiRunId() != null) {
			markAiRunFailed(prepared.aiRunId(), message);
		}
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void verifyRunOwner(Member member, Long aiRunId) {
		if (member == null || aiRunId == null) {
			throw new CustomException(ErrorCode.NOT_FOUND);
		}
		boolean owned = aiRunRepository.findById(aiRunId)
			.map(aiRun -> aiRun.getChatRoom().getMember().getId().equals(member.getId()))
			.orElse(false);
		if (!owned) {
			throw new CustomException(ErrorCode.NOT_FOUND);
		}
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public boolean markRunCancelled(Long aiRunId) {
		Boolean cancelled = writeTransaction.execute(status -> aiRunRepository.findByIdForUpdate(aiRunId)
			.map(AiRun::markCancelled)
			.orElse(false));
		return Boolean.TRUE.equals(cancelled);
	}

	private InitialUserWrite saveApprovedUserMessage(Long chatRoomId, String displayContent, MaskingResult maskingResult,
		List<MultipartFile> files) {
		return writeTransaction.execute(status -> {
			ChatRoom managedChatRoom = chatRoomRepository.findById(chatRoomId)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
			fileAssetService.storeUploads(managedChatRoom, files);
			ChatMessage userMessage = chatMessageRepository.save(
				ChatMessage.create(managedChatRoom, MessageRole.USER, displayContent, maskingResult.maskedText()));
			managedChatRoom.touch();
			AiRun aiRun = aiRunRepository.save(AiRun.createApproved(managedChatRoom, userMessage));
			saveMaskingAudit(aiRun, maskingResult.detections());
			return new InitialUserWrite(managedChatRoom.getId(), userMessage.getId(), aiRun.getId(), null);
		});
	}

	/**
	 * 마지막 AI 답변과 같은 대화 맥락으로 새 답변을 생성합니다. (답변 재생성 버튼)
	 *
	 * <p>외부 AI 호출에 실패해도 기존 답변이 사라지지 않도록 호출 전에는 삭제하지 않습니다.
	 * 새 답변 생성과 원복이 성공한 뒤 같은 쓰기 트랜잭션에서 기존 답변을 교체합니다.
	 * 이전 답변의 AiRun·마스킹 감사 기록은 이력 추적을 위해 삭제하지 않습니다.
	 */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public ChatSendResponse regenerate(Member member, Long chatRoomId) {
		if (chatRoomId == null) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
		InitialUserWrite write = writeTransaction.execute(status -> {
			ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
					ChatRoomStatus.ACTIVE)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

			// createdAt은 같은 요청에서 만들어진 행끼리 동률일 수 있어, 확정적인 id 기준으로 정렬한다
			List<ChatMessage> messages = new ArrayList<>(
				chatMessageRepository.findRecentByChatRoomIdOrderByIdDesc(chatRoom.getId(), CONTEXT_MESSAGE_LIMIT));
			messages.sort(Comparator.comparing(ChatMessage::getId).reversed());

			if (messages.isEmpty() || messages.get(0).getRole() != MessageRole.ASSISTANT) {
				throw new CustomException(ErrorCode.INVALID_REQUEST, "다시 생성할 AI 답변이 없습니다.");
			}
			ChatMessage lastUserMessage = messages.stream()
				.filter(message -> message.getRole() == MessageRole.USER)
				.findFirst()
				.orElseThrow(() -> new CustomException(ErrorCode.INVALID_REQUEST, "다시 생성할 AI 답변이 없습니다."));

			ChatMessage lastAssistantMessage = messages.get(0);
			chatRoom.touch();
			AiRun aiRun = aiRunRepository.save(AiRun.createApproved(chatRoom, lastUserMessage));
			return new InitialUserWrite(chatRoom.getId(), lastUserMessage.getId(), aiRun.getId(),
				lastAssistantMessage.getId());
		});

		// 재생성은 새 입력이 없으므로 마스킹 요약·탐지 정보 없이 답변만 새로 만든다
		return generateAnswer(write.chatRoomId(), write.userMessageId(), write.aiRunId(), Map.of(), List.of(),
			AiProgressListener.NONE, write.previousAssistantMessageId());
	}

	/**
	 * 채팅방의 현재 대화 맥락으로 GPT를 호출해 답변을 만들고 저장합니다.
	 * (최초 전송과 답변 재생성이 공유하는 공통 경로)
	 */
	private ChatSendResponse generateAnswer(Long chatRoomId, Long userMessageId, Long aiRunId,
		Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections,
		AiProgressListener progressListener, Long previousAssistantMessageId) {
		try {
			markAiRunCalling(aiRunId);
			ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
			AiGatewayResponse response = aiGateway.generate(
				buildPromptMessages(chatRoom, previousAssistantMessageId), progressListener);
			String maskedAnswer = response.maskedText();
			String restoredAnswer = maskingService.restore(chatRoom.getId(), maskedAnswer);
			return saveAssistantAnswer(chatRoomId, userMessageId, aiRunId, restoredAnswer, maskedAnswer,
				response.model(), response.inputTokens(), response.outputTokens(), summary, detections,
				previousAssistantMessageId);
		} catch (RuntimeException e) {
			markAiRunFailed(aiRunId, e.getMessage());
			if (e instanceof CustomException customException) {
				throw customException;
			}
			throw new CustomException(ErrorCode.AI_SERVICE_UNAVAILABLE, e);
		}
	}

	private void markAiRunCalling(Long aiRunId) {
		writeTransaction.executeWithoutResult(status -> aiRunRepository.findByIdForUpdate(aiRunId)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND))
			.markCalling(modelName));
	}

	private void markAiRunFailed(Long aiRunId, String message) {
		writeTransaction.executeWithoutResult(status -> aiRunRepository.findByIdForUpdate(aiRunId)
			.ifPresent(aiRun -> aiRun.markFailed(message)));
	}

	private ChatSendResponse saveAssistantAnswer(Long chatRoomId, Long userMessageId, Long aiRunId,
		String restoredAnswer, String maskedAnswer, String responseModel, Integer inputTokens, Integer outputTokens,
		Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections,
		Long previousAssistantMessageId) {
		return writeTransaction.execute(status -> {
			ChatRoom chatRoom = chatRoomRepository.findById(chatRoomId)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
			ChatMessage userMessage = chatMessageRepository.findById(userMessageId)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
			// 취소와 완료가 경합하면 먼저 행 잠금을 얻은 상태 전이만 성공한다. 취소가 먼저
			// 확정된 경우 markCompleted가 거부되어 답변/생성 파일 저장 전체가 롤백된다.
			AiRun aiRun = aiRunRepository.findByIdForUpdate(aiRunId)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

			ChatMessage previousAssistant = null;
			if (previousAssistantMessageId != null) {
				previousAssistant = chatMessageRepository.findById(previousAssistantMessageId)
					.filter(message -> message.getChatRoom().getId().equals(chatRoomId)
						&& message.getRole() == MessageRole.ASSISTANT)
					.orElseThrow(() -> new CustomException(ErrorCode.INVALID_REQUEST,
						"교체할 이전 AI 답변을 찾을 수 없습니다."));
			}

			// 원복이 끝난 응답에서 파일 블록을 실제 파일로 변환하고 안내 문구로 치환.
			// (원복 후에 실행해야 파일에 토큰이 아닌 원본값이 담긴다)
			GeneratedFileService.Outcome fileOutcome = generatedFileService.materialize(chatRoom, restoredAnswer);

			if (previousAssistant != null) {
				generatedFileService.retireGeneratedFilesFromAnswer(chatRoom, previousAssistant.getOriginalContent());
				chatMessageRepository.delete(previousAssistant);
			}

			// 화면·원본 보관용에는 치환된 본문을, GPT 컨텍스트용(maskedContent)에는
			// 파일 블록이 남은 마스킹 응답을 그대로 저장해 모델이 자기 산출물을 기억하게 한다.
			ChatMessage assistantMessage = chatMessageRepository.save(
				ChatMessage.create(chatRoom, MessageRole.ASSISTANT, fileOutcome.displayContent(), maskedAnswer));

			aiRun.markCompleted(responseModel, inputTokens, outputTokens);

			return ChatSendResponse.completed(chatRoom.getId(), userMessage.getId(), assistantMessage.getId(),
				fileOutcome.displayContent(), summary, detections, fileOutcome.files());
		});
	}

	private void validateRequest(ChatSendRequest request) {
		if (request == null || request.content() == null || request.content().isBlank()) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}

	private ChatRoom resolveChatRoom(Member member, ChatSendRequest request, String displayContent) {
		if (request.chatRoomId() != null) {
			return chatRoomRepository.findByIdAndMember_IdAndStatus(request.chatRoomId(), member.getId(),
					ChatRoomStatus.ACTIVE)
				.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
		}

		return chatRoomRepository.save(ChatRoom.create(member, createTitle(displayContent)));
	}

	private String createTitle(String content) {
		String normalized = content.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= 40) {
			return normalized;
		}
		return normalized.substring(0, 40);
	}

	private String buildDisplayContent(String baseContent, List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return baseContent;
		}

		StringBuilder builder = new StringBuilder();
		if (!baseContent.isBlank()) {
			builder.append(baseContent).append("\n\n");
		}
		builder.append("첨부 파일");
		for (MultipartFile file : files) {
			if (file != null && !file.isEmpty()) {
				builder.append("\n- ").append(file.getOriginalFilename());
			}
		}
		return builder.toString();
	}

	private MaskingResult applyMasking(Long chatRoomId, ChatSendRequest request) {
		MaskingResult result = maskingService.mask(chatRoomId, request.content());
		if (request.manualMasks() == null || request.manualMasks().isEmpty()) {
			return result;
		}

		MaskingResult current = result;
		for (ManualMaskRequest manualMask : request.manualMasks()) {
			if (manualMask == null || manualMask.value() == null || manualMask.value().isBlank()) {
				continue;
			}
			MaskingType type = manualMask.type() == null ? MaskingType.CUSTOM : manualMask.type();
			MaskingResult manualResult = maskingService.maskManually(chatRoomId, current.maskedText(),
				manualMask.value(), type);

			List<Detection> merged = new ArrayList<>(current.detections());
			merged.addAll(manualResult.detections());
			current = new MaskingResult(request.content(), manualResult.maskedText(), List.copyOf(merged));
		}
		return current;
	}

	private void saveMaskingAudit(AiRun aiRun, List<Detection> detections) {
		if (detections.isEmpty()) {
			return;
		}

		List<MaskingEntity> entities = detections.stream()
			.map(detection -> MaskingEntity.create(aiRun, detection.type(), detection.token(),
				detection.startIndex(), detection.endIndex()))
			.toList();
		maskingEntityRepository.saveAll(entities);
	}

	private List<AiPromptMessage> buildPromptMessages(ChatRoom chatRoom, Long excludedAssistantMessageId) {
		List<ChatMessage> recentMessages = new ArrayList<>(
			chatMessageRepository.findRecentByChatRoomIdOrderByIdDesc(chatRoom.getId(), CONTEXT_MESSAGE_LIMIT));
		Collections.reverse(recentMessages);

		List<AiPromptMessage> messages = new ArrayList<>();
		messages.add(new AiPromptMessage("system", SafeMaskSystemPrompt.DEFAULT));

		for (ChatMessage chatMessage : recentMessages) {
			if (excludedAssistantMessageId != null && excludedAssistantMessageId.equals(chatMessage.getId())) {
				continue;
			}
			if (chatMessage.getMaskedContent() == null || chatMessage.getMaskedContent().isBlank()) {
				continue;
			}
			if (chatMessage.getRole() == MessageRole.USER) {
				messages.add(new AiPromptMessage("user", chatMessage.getMaskedContent()));
			} else {
				messages.add(new AiPromptMessage("assistant", chatMessage.getMaskedContent()));
			}
		}
		return messages;
	}
}
