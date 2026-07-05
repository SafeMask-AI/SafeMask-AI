package haitai.safemask.domain.chatroom.dto;

import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatmessage.enums.MessageRole;
import java.time.LocalDateTime;

public record ChatMessageHistoryResponse(
	Long id,
	MessageRole role,
	String content,
	LocalDateTime createdAt
) {
	public static ChatMessageHistoryResponse from(ChatMessage message) {
		return new ChatMessageHistoryResponse(
			message.getId(),
			message.getRole(),
			message.getOriginalContent(),
			message.getCreatedAt()
		);
	}
}
