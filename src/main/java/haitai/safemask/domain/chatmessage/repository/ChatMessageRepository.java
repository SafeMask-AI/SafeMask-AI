package haitai.safemask.domain.chatmessage.repository;

import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
	/**
	 * 구버전 Oracle에서 파생 exists 쿼리가 생성하는 {@code FETCH FIRST ? ROWS ONLY}는
	 * ORA-00933을 일으킬 수 있으므로 제한절 없는 COUNT 쿼리로 존재 여부를 확인합니다.
	 */
	@Query("select count(cm.id) from ChatMessage cm where cm.chatRoom.id = :chatRoomId")
	long countByChatRoomId(@Param("chatRoomId") Long chatRoomId);

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
