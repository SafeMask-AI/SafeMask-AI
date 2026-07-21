package haitai.safemask.domain.chatroom.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.chatmessage.approval.MaskingApprovalService;
import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.masking.config.MaskingProperties;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.member.entity.Member;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ExpiredPreviewCleanupServiceTest {

	@Mock private ChatRoomRepository chatRoomRepository;
	@Mock private ChatMessageRepository chatMessageRepository;
	@Mock private MaskingApprovalService maskingApprovalService;
	@Mock private MaskingService maskingService;

	@Test
	@DisplayName("만료된 빈 미리보기 방은 승인 스냅샷과 원복 매핑을 지운 뒤 삭제한다")
	void removesExpiredEmptyPreviewArtifacts() {
		MaskingProperties properties = new MaskingProperties();
		properties.setApprovalTtl(Duration.ofMinutes(15));
		ExpiredPreviewCleanupService service = new ExpiredPreviewCleanupService(chatRoomRepository,
			chatMessageRepository, maskingApprovalService, maskingService, properties);
		Member member = member(7L);
		ChatRoom emptyRoom = room(member, 31L);
		when(chatRoomRepository.findExpiredEmptyRooms(eq(ChatRoomStatus.ACTIVE.name()),
			any(LocalDateTime.class), eq(100))).thenReturn(List.of(emptyRoom));
		when(chatMessageRepository.existsByChatRoom_Id(31L)).thenReturn(false);

		service.cleanup();

		verify(maskingApprovalService).discardForRoom(member, 31L);
		verify(maskingService).clearMappings(31L);
		verify(chatRoomRepository).delete(emptyRoom);
	}

	@Test
	@DisplayName("조회 뒤 메시지가 생긴 방은 만료 정리에서 다시 확인하고 보존한다")
	void preservesRoomThatReceivedMessageDuringCleanup() {
		MaskingProperties properties = new MaskingProperties();
		ExpiredPreviewCleanupService service = new ExpiredPreviewCleanupService(chatRoomRepository,
			chatMessageRepository, maskingApprovalService, maskingService, properties);
		ChatRoom room = room(member(7L), 31L);
		when(chatRoomRepository.findExpiredEmptyRooms(eq(ChatRoomStatus.ACTIVE.name()),
			any(LocalDateTime.class), eq(100))).thenReturn(List.of(room));
		when(chatMessageRepository.existsByChatRoom_Id(31L)).thenReturn(true);

		service.cleanup();

		verify(maskingApprovalService, never()).discardForRoom(any(), any());
		verify(maskingService, never()).clearMappings(any());
		verify(chatRoomRepository, never()).delete(any());
	}

	private Member member(Long id) {
		Member member = Member.create("employee", "encoded", "사용자", "employee@example.com", "개발");
		ReflectionTestUtils.setField(member, "id", id);
		return member;
	}

	private ChatRoom room(Member member, Long id) {
		ChatRoom room = ChatRoom.create(member, "미리보기");
		ReflectionTestUtils.setField(room, "id", id);
		return room;
	}
}
