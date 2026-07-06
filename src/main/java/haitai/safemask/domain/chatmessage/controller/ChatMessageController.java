package haitai.safemask.domain.chatmessage.controller;

import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/messages")
public class ChatMessageController {

	private final ChatMessageService chatMessageService;
	private final ObjectMapper objectMapper;

	/**
	 * 채팅 메시지를 전송합니다.
	 *
	 * <p>민감정보가 탐지되면 previewRequired=true 응답을 먼저 내려 사용자의 확인을 받습니다.
	 * 탐지 0건이거나 approved=true 요청이면 마스킹된 대화 맥락만 GPT로 전송합니다.
	 */
	@PostMapping
	public ResponseEntity<ChatSendResponse> send(@AuthenticationPrincipal Member member,
		@RequestBody ChatSendRequest request) {
		return ResponseEntity.ok(chatMessageService.send(member, request));
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
			throw new CustomException(ErrorCode.INVALID_REQUEST, "추가 마스킹 요청 형식이 올바르지 않습니다.");
		}
	}
}
