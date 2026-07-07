package haitai.safemask.domain.chatmessage.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.chatmessage.dto.ChatRegenerateRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendResponse;
import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.chatmessage.service.ChatMessageService;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/messages")
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final ObjectMapper objectMapper;

	@PostMapping
	public ResponseEntity<ChatSendResponse> send(@AuthenticationPrincipal Member member,
		@RequestBody ChatSendRequest request) {
		return ResponseEntity.ok(chatMessageService.send(member, request));
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
		@RequestParam String content,
		@RequestParam(defaultValue = "false") Boolean approved,
		@RequestParam(defaultValue = "[]") String manualMasks,
		@RequestPart(value = "files", required = false) List<MultipartFile> files) {

		List<ManualMaskRequest> masks = parseManualMasks(manualMasks);
		ChatSendRequest request = new ChatSendRequest(chatRoomId, content, approved, masks);
		return ResponseEntity.ok(chatMessageService.sendWithFiles(member, request, files));
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
