package haitai.safemask.domain.chatroom.repository;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	Optional<ChatRoom> findByIdAndMember_IdAndStatus(Long id, Long memberId, ChatRoomStatus status);

	List<ChatRoom> findByMember_IdAndStatusOrderByUpdatedAtDesc(Long memberId, ChatRoomStatus status);

	@Query(value = """
		SELECT *
		  FROM (
				SELECT cr.*
				  FROM MASK_CHATROOM cr
				 WHERE cr.member_id = :memberId
				   AND cr.status = :status
				   AND EXISTS (
						SELECT 1
						  FROM MASK_CHAT_MESSAGE cm
						 WHERE cm.chatroom_id = cr.id
				   )
				 ORDER BY cr.updated_at DESC
		  )
		 WHERE ROWNUM <= :limit
		""", nativeQuery = true)
	List<ChatRoom> findRecentByMemberIdAndStatus(@Param("memberId") Long memberId,
		@Param("status") String status, @Param("limit") int limit);
}
