package haitai.safemask.domain.chatroom.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.fileasset.service.FileAssetService;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.member.entity.Member;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatRoomServiceTest {

	private ChatRoomRepository chatRoomRepository;
	private ChatMessageRepository chatMessageRepository;
	private MaskingService maskingService;
	private ChatRoomService chatRoomService;

	@BeforeEach
	void setUp() {
		chatRoomRepository = mock(ChatRoomRepository.class);
		chatMessageRepository = mock(ChatMessageRepository.class);
		maskingService = mock(MaskingService.class);
		chatRoomService = new ChatRoomService(chatRoomRepository, chatMessageRepository, maskingService,
			mock(FileAssetService.class));
	}

	@Test
	void discardEmptyPreviewDeletesOnlyEmptyRoomAndMaskingMappings() {
		Member member = mock(Member.class);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(member.getId()).thenReturn(7L);
		when(chatRoom.getId()).thenReturn(23L);
		when(chatRoomRepository.findByIdAndMember_IdAndStatus(23L, 7L, ChatRoomStatus.ACTIVE))
			.thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.existsByChatRoom_Id(23L)).thenReturn(false);

		chatRoomService.discardEmptyPreview(member, 23L);

		verify(maskingService).clearMappings(23L);
		verify(chatRoomRepository).delete(chatRoom);
	}

	@Test
	void discardEmptyPreviewPreservesRoomAfterAnyMessageWasSaved() {
		Member member = mock(Member.class);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(member.getId()).thenReturn(7L);
		when(chatRoom.getId()).thenReturn(23L);
		when(chatRoomRepository.findByIdAndMember_IdAndStatus(23L, 7L, ChatRoomStatus.ACTIVE))
			.thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.existsByChatRoom_Id(23L)).thenReturn(true);

		chatRoomService.discardEmptyPreview(member, 23L);

		verify(maskingService, never()).clearMappings(23L);
		verify(chatRoomRepository, never()).delete(chatRoom);
	}
}
