package haitai.safemask.domain.chatmessage.repository;

import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
	boolean existsByChatRoom_Id(Long chatRoomId);

	List<ChatMessage> findByChatRoomOrderByCreatedAtDesc(ChatRoom chatRoom);

	List<ChatMessage> findByChatRoomOrderByCreatedAtAsc(ChatRoom chatRoom);

	@Query(value = """
		SELECT *
		  FROM (
				SELECT cm.*
				  FROM MASK_CHAT_MESSAGE cm
				 WHERE cm.chatroom_id = :chatRoomId
				 ORDER BY cm.id DESC
		  )
		 WHERE ROWNUM <= :limit
		""", nativeQuery = true)
	List<ChatMessage> findRecentByChatRoomIdOrderByIdDesc(@Param("chatRoomId") Long chatRoomId,
		@Param("limit") int limit);
}
