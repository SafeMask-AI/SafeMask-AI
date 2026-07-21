package haitai.safemask.domain.chatmessage.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.chatmessage.dto.ChatRegenerateRequest;
import haitai.safemask.domain.chatmessage.dto.ChatCancelResponse;
import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendResponse;
import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.chatmessage.approval.MaskingApprovalService;
import haitai.safemask.domain.chatmessage.service.ChatMessageService;
import haitai.safemask.domain.chatmessage.service.ChatMessageStreamService;
import haitai.safemask.domain.chatmessage.service.MaskingPreviewDownloadService;
import haitai.safemask.domain.chatmessage.service.MaskingPreviewDownloadService.PreviewDownloadFile;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/messages")
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final ChatMessageStreamService chatMessageStreamService;
	private final MaskingPreviewDownloadService maskingPreviewDownloadService;
	private final MaskingApprovalService maskingApprovalService;
	private final ObjectMapper objectMapper;

	@PostMapping
	public ResponseEntity<ChatSendResponse> send(@AuthenticationPrincipal Member member,
		@RequestBody ChatSendRequest request) {
		return ResponseEntity.ok(chatMessageService.send(member, request));
	}

	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> sendStream(@AuthenticationPrincipal Member member,
		@RequestBody ChatSendRequest request) {
		return streamResponse(chatMessageStreamService.send(member, request));
	}

	/** 답변 재생성: 채팅방의 마지막 AI 답변을 지우고 같은 맥락으로 다시 생성합니다. */
	@PostMapping("/regenerate")
	public ResponseEntity<ChatSendResponse> regenerate(@AuthenticationPrincipal Member member,
		@RequestBody ChatRegenerateRequest request) {
		return ResponseEntity.ok(chatMessageService.regenerate(member, request.chatRoomId()));
	}

	@PostMapping(value = "/with-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ChatSendResponse> sendWithFiles(@AuthenticationPrincipal Member member,
		@RequestParam(required = false) Long chatRoomId,
		@RequestParam(required = false, defaultValue = "") String content,
		@RequestParam(required = false) String approvalId,
		@RequestParam(defaultValue = "[]") String manualMasks,
		@RequestPart(value = "files", required = false) List<MultipartFile> files) {

		List<ManualMaskRequest> masks = parseManualMasks(manualMasks);
		ChatSendRequest request = new ChatSendRequest(chatRoomId, content, approvalId, masks);
		return ResponseEntity.ok(chatMessageService.sendWithFiles(member, request, files));
	}

	@PostMapping(value = "/with-files/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
		produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> sendWithFilesStream(@AuthenticationPrincipal Member member,
		@RequestParam(required = false) Long chatRoomId,
		@RequestParam(required = false, defaultValue = "") String content,
		@RequestParam(required = false) String approvalId,
		@RequestParam(defaultValue = "[]") String manualMasks,
		@RequestPart(value = "files", required = false) List<MultipartFile> files) {
		ChatSendRequest request = new ChatSendRequest(chatRoomId, content, approvalId, parseManualMasks(manualMasks));
		return streamResponse(chatMessageStreamService.sendWithFiles(member, request, files));
	}

	private ResponseEntity<SseEmitter> streamResponse(SseEmitter emitter) {
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform")
			.header("X-Accel-Buffering", "no")
			.body(emitter);
	}

	@DeleteMapping("/runs/{aiRunId}")
	public ResponseEntity<ChatCancelResponse> cancelRun(@AuthenticationPrincipal Member member,
		@PathVariable Long aiRunId) {
		return ResponseEntity.ok(new ChatCancelResponse(chatMessageStreamService.cancel(member, aiRunId)));
	}

	/** 사용자가 미리보기를 취소하거나 다시 수정할 때 대기 중인 원문 스냅샷을 즉시 파기합니다. */
	@DeleteMapping("/previews/{approvalId}")
	public ResponseEntity<Void> discardPreview(@AuthenticationPrincipal Member member,
		@PathVariable String approvalId) {
		maskingApprovalService.discard(member, approvalId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping(value = "/preview-download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<byte[]> downloadPreview(@AuthenticationPrincipal Member member,
		@RequestParam Long chatRoomId,
		@RequestParam(required = false, defaultValue = "") String content,
		@RequestParam(defaultValue = "[]") String manualMasks,
		@RequestPart(value = "files", required = false) List<MultipartFile> files) {

		PreviewDownloadFile file = maskingPreviewDownloadService.build(member, chatRoomId, content,
			parseManualMasks(manualMasks), files);
		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(file.fileName(), StandardCharsets.UTF_8)
			.build();

		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.contentType(MediaType.parseMediaType(file.contentType()))
			.body(file.bytes());
	}


	private List<ManualMaskRequest> parseManualMasks(String manualMasks) {
		try {
			return objectMapper.readValue(manualMasks, new TypeReference<>() {
			});
		} catch (JsonProcessingException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, "Invalid manual mask request format.");
		}
	}
}
