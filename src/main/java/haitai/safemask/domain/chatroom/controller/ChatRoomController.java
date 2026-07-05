package haitai.safemask.domain.chatroom.controller;

import haitai.safemask.domain.chatroom.dto.ChatMessageHistoryResponse;
import haitai.safemask.domain.chatroom.dto.ChatRoomResponse;
import haitai.safemask.domain.chatroom.service.ChatRoomService;
import haitai.safemask.domain.member.entity.Member;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/chat/rooms")
public class ChatRoomController {

	private final ChatRoomService chatRoomService;

	@GetMapping
	public ResponseEntity<List<ChatRoomResponse>> rooms(@AuthenticationPrincipal Member member) {
		return ResponseEntity.ok(chatRoomService.findMyRooms(member));
	}

	@GetMapping("/{chatRoomId}/messages")
	public ResponseEntity<List<ChatMessageHistoryResponse>> messages(@AuthenticationPrincipal Member member,
		@PathVariable Long chatRoomId) {
		return ResponseEntity.ok(chatRoomService.findMessages(member, chatRoomId));
	}

	@DeleteMapping("/{chatRoomId}")
	public ResponseEntity<Void> archive(@AuthenticationPrincipal Member member, @PathVariable Long chatRoomId) {
		chatRoomService.archive(member, chatRoomId);
		return ResponseEntity.noContent().build();
	}
}
