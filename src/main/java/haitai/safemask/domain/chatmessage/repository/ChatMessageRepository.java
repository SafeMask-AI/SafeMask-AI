package haitai.safemask.domain.chatmessage.repository;

import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
}
