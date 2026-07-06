package haitai.safemask.domain.chatmessage.repository;

import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

	List<ChatMessage> findByChatRoomOrderByCreatedAtDesc(ChatRoom chatRoom);

	List<ChatMessage> findByChatRoomOrderByCreatedAtAsc(ChatRoom chatRoom);
}
