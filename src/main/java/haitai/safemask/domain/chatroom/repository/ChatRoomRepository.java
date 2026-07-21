package haitai.safemask.domain.chatroom.repository;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import java.time.LocalDateTime;
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

	// 구버전 Oracle 호환을 위해 Pageable의 FETCH FIRST 대신 기존 목록 조회와 같은 ROWNUM을 사용합니다.
	@Query(value = """
		SELECT *
		  FROM (
				SELECT cr.*
				  FROM MASK_CHATROOM cr
				 WHERE cr.status = :status
				   AND cr.updated_at < :cutoff
				   AND NOT EXISTS (
						SELECT 1
						  FROM MASK_CHAT_MESSAGE cm
						 WHERE cm.chatroom_id = cr.id
				   )
				 ORDER BY cr.updated_at ASC
		  )
		 WHERE ROWNUM <= :limit
		""", nativeQuery = true)
	List<ChatRoom> findExpiredEmptyRooms(@Param("status") String status,
		@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
