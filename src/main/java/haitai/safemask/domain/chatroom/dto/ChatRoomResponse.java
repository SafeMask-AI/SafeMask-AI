package haitai.safemask.domain.chatroom.dto;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import java.time.LocalDateTime;

public record ChatRoomResponse(
	Long id,
	String title,
	LocalDateTime updatedAt
) {
	public static ChatRoomResponse from(ChatRoom chatRoom) {
		return new ChatRoomResponse(chatRoom.getId(), chatRoom.getTitle(), chatRoom.getUpdatedAt());
	}
}
