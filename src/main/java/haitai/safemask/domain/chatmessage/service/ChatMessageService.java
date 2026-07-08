package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.domain.airun.entity.AiRun;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
	private final ChatModel chatModel;
	private final String modelName;

	public ChatMessageService(ChatRoomRepository chatRoomRepository,
		ChatMessageRepository chatMessageRepository,
		AiRunRepository aiRunRepository,
		MaskingEntityRepository maskingEntityRepository,
		MaskingService maskingService,
		AttachmentTextExtractor attachmentTextExtractor,
		GeneratedFileService generatedFileService,
		FileAssetService fileAssetService,
		ChatModel chatModel,
		@Value("${safemask.ai.model:gpt-5.5}") String modelName) {
		this.chatRoomRepository = chatRoomRepository;
		this.chatMessageRepository = chatMessageRepository;
		this.aiRunRepository = aiRunRepository;
		this.maskingEntityRepository = maskingEntityRepository;
		this.maskingService = maskingService;
		this.attachmentTextExtractor = attachmentTextExtractor;
		this.generatedFileService = generatedFileService;
		this.fileAssetService = fileAssetService;
		this.chatModel = chatModel;
		this.modelName = modelName;
	}

	/**
	 * 사용자의 채팅 입력을 처리합니다.
	 *
	 * <p>민감정보가 탐지됐고 사용자가 아직 승인하지 않았다면 GPT 호출 없이 미리보기만 반환합니다.
	 * 탐지 0건이거나 approved=true인 요청은 maskedContent 기준으로 이전 대화 맥락을 구성해
	 * GPT에 전송하고, 응답은 Redis 매핑으로 원복해 저장합니다.
	 */
	@Transactional
	public ChatSendResponse send(Member member, ChatSendRequest request) {
		return sendInternal(member, request, request.content(), null);
	}

	@Transactional
	public ChatSendResponse sendWithFiles(Member member, ChatSendRequest request, List<MultipartFile> files) {
		String attachmentText = attachmentTextExtractor.extract(files);
		String baseContent = request.content() == null ? "" : request.content().trim();
		String processingContent = (baseContent + attachmentText).trim();
		String displayContent = buildDisplayContent(baseContent, files);
		return sendInternal(member, new ChatSendRequest(request.chatRoomId(), processingContent, request.approved(),
			request.manualMasks()), displayContent, files);
	}

	private ChatSendResponse sendInternal(Member member, ChatSendRequest request, String displayContent,
		List<MultipartFile> files) {
		validateRequest(request);
		boolean forcePreview = files != null && !files.isEmpty();

		ChatRoom chatRoom = resolveChatRoom(member, request, displayContent);
		MaskingResult maskingResult = applyMasking(chatRoom.getId(), request);
		List<MaskingDetectionResponse> detections = maskingResult.detections()
			.stream()
			.map(MaskingDetectionResponse::from)
			.toList();

		if ((forcePreview || maskingResult.hasDetections()) && !request.isApproved()) {
			return ChatSendResponse.preview(chatRoom.getId(), maskingResult.maskedText(),
				maskingResult.summary(), detections);
		}

		// 첨부 원본을 사내 스토리지에 보관한다. (미리보기 단계에서는 저장하지 않고,
		// 사용자가 전송을 확정한 시점에만 보관 — AI 편집 요청 시 카피의 출발점이 된다)
		fileAssetService.storeUploads(chatRoom, files);

		ChatMessage userMessage = chatMessageRepository.save(
			ChatMessage.create(chatRoom, MessageRole.USER, displayContent, maskingResult.maskedText()));
		chatRoom.touch();
		AiRun aiRun = aiRunRepository.save(AiRun.createApproved(chatRoom, userMessage));
		saveMaskingAudit(aiRun, maskingResult.detections());

		return generateAnswer(chatRoom, userMessage, aiRun, maskingResult.summary(), detections);
	}

	/**
	 * 마지막 AI 답변을 지우고 같은 대화 맥락으로 다시 생성합니다. (답변 재생성 버튼)
	 *
	 * <p>사용자 메시지는 그대로 두고 마지막 ASSISTANT 메시지만 삭제한 뒤 GPT를 다시
	 * 호출하므로, 마스킹·원복 흐름은 최초 전송과 동일하게 동작합니다.
	 * 이전 답변의 AiRun·마스킹 감사 기록은 이력 추적을 위해 삭제하지 않습니다.
	 */
	@Transactional
	public ChatSendResponse regenerate(Member member, Long chatRoomId) {
		if (chatRoomId == null) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		// createdAt은 같은 요청에서 만들어진 행끼리 동률일 수 있어, 확정적인 id 기준으로 정렬한다
		List<ChatMessage> messages = new ArrayList<>(chatMessageRepository.findByChatRoomOrderByCreatedAtDesc(chatRoom));
		messages.sort(Comparator.comparing(ChatMessage::getId).reversed());

		if (messages.isEmpty() || messages.get(0).getRole() != MessageRole.ASSISTANT) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, "다시 생성할 AI 답변이 없습니다.");
		}
		ChatMessage lastUserMessage = messages.stream()
			.filter(message -> message.getRole() == MessageRole.USER)
			.findFirst()
			.orElseThrow(() -> new CustomException(ErrorCode.INVALID_REQUEST, "다시 생성할 AI 답변이 없습니다."));

		ChatMessage lastAssistantMessage = messages.get(0);
		generatedFileService.retireGeneratedFilesFromAnswer(chatRoom, lastAssistantMessage.getOriginalContent());
		chatMessageRepository.delete(lastAssistantMessage);
		chatRoom.touch();
		AiRun aiRun = aiRunRepository.save(AiRun.createApproved(chatRoom, lastUserMessage));

		// 재생성은 새 입력이 없으므로 마스킹 요약·탐지 정보 없이 답변만 새로 만든다
		return generateAnswer(chatRoom, lastUserMessage, aiRun, Map.of(), List.of());
	}

	/**
	 * 채팅방의 현재 대화 맥락으로 GPT를 호출해 답변을 만들고 저장합니다.
	 * (최초 전송과 답변 재생성이 공유하는 공통 경로)
	 */
	private ChatSendResponse generateAnswer(ChatRoom chatRoom, ChatMessage userMessage, AiRun aiRun,
		Map<MaskingType, Long> summary, List<MaskingDetectionResponse> detections) {
		try {
			aiRun.markCalling(modelName);
			ChatResponse response = chatModel.call(new Prompt(buildPromptMessages(chatRoom)));
			String maskedAnswer = response.getResult().getOutput().getText();
			String restoredAnswer = maskingService.restore(chatRoom.getId(), maskedAnswer);

			// 원복이 끝난 응답에서 파일 블록을 실제 파일로 변환하고 안내 문구로 치환.
			// (원복 후에 실행해야 파일에 토큰이 아닌 원본값이 담긴다)
			GeneratedFileService.Outcome fileOutcome = generatedFileService.materialize(chatRoom, restoredAnswer);

			// 화면·원본 보관용에는 치환된 본문을, GPT 컨텍스트용(maskedContent)에는
			// 파일 블록이 남은 마스킹 응답을 그대로 저장해 모델이 자기 산출물을 기억하게 한다.
			ChatMessage assistantMessage = chatMessageRepository.save(
				ChatMessage.create(chatRoom, MessageRole.ASSISTANT, fileOutcome.displayContent(), maskedAnswer));

			Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
			aiRun.markCompleted(resolveModel(response), usage == null ? null : usage.getPromptTokens(),
				usage == null ? null : usage.getCompletionTokens());

			return ChatSendResponse.completed(chatRoom.getId(), userMessage.getId(), assistantMessage.getId(),
				fileOutcome.displayContent(), summary, detections, fileOutcome.files());
		} catch (RuntimeException e) {
			aiRun.markFailed(e.getMessage());
			throw new CustomException(ErrorCode.AI_SERVICE_UNAVAILABLE, e);
		}
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

	private List<Message> buildPromptMessages(ChatRoom chatRoom) {
		List<ChatMessage> recentMessages = new ArrayList<>(
			chatMessageRepository.findByChatRoomOrderByCreatedAtDesc(chatRoom));
		if (recentMessages.size() > CONTEXT_MESSAGE_LIMIT) {
			recentMessages = new ArrayList<>(recentMessages.subList(0, CONTEXT_MESSAGE_LIMIT));
		}
		Collections.reverse(recentMessages);

		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(SafeMaskSystemPrompt.DEFAULT));

		for (ChatMessage chatMessage : recentMessages) {
			if (chatMessage.getMaskedContent() == null || chatMessage.getMaskedContent().isBlank()) {
				continue;
			}
			if (chatMessage.getRole() == MessageRole.USER) {
				messages.add(new UserMessage(chatMessage.getMaskedContent()));
			} else {
				messages.add(new AssistantMessage(chatMessage.getMaskedContent()));
			}
		}
		return messages;
	}

	private String resolveModel(ChatResponse response) {
		String responseModel = response.getMetadata() == null ? null : response.getMetadata().getModel();
		return responseModel == null || responseModel.isBlank() ? modelName : responseModel;
	}
}
