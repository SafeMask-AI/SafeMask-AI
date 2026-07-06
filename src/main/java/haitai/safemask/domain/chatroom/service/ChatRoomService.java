package haitai.safemask.domain.chatroom.service;

import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.dto.ChatMessageHistoryResponse;
import haitai.safemask.domain.chatroom.dto.ChatRoomResponse;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final MaskingService maskingService;

	public List<ChatRoomResponse> findMyRooms(Member member) {
		return chatRoomRepository.findByMember_IdAndStatusOrderByUpdatedAtDesc(member.getId(),
				ChatRoomStatus.ACTIVE)
			.stream()
			.limit(30)
			.map(ChatRoomResponse::from)
			.toList();
	}

	public List<ChatMessageHistoryResponse> findMessages(Member member, Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		return chatMessageRepository.findByChatRoomOrderByCreatedAtAsc(chatRoom)
			.stream()
			.map(ChatMessageHistoryResponse::from)
			.toList();
	}

	@Transactional
	public void archive(Member member, Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		chatRoom.archive();
		maskingService.clearMappings(chatRoom.getId());
	}
}
