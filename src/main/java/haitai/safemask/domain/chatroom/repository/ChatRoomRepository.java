package haitai.safemask.domain.chatroom.repository;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	Optional<ChatRoom> findByIdAndMember_IdAndStatus(Long id, Long memberId, ChatRoomStatus status);

	List<ChatRoom> findTop30ByMember_IdAndStatusOrderByUpdatedAtDesc(Long memberId, ChatRoomStatus status);
}
