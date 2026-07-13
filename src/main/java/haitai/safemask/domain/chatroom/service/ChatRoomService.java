package haitai.safemask.domain.chatroom.service;

import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.dto.ChatMessageHistoryResponse;
import haitai.safemask.domain.chatroom.dto.ChatRoomResponse;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.fileasset.service.FileAssetService;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatRoomService {

	private static final int ROOM_LIST_LIMIT = 30;
	private static final int MESSAGE_HISTORY_LIMIT = 200;

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final MaskingService maskingService;
	private final FileAssetService fileAssetService;

	public List<ChatRoomResponse> findMyRooms(Member member) {
		return chatRoomRepository.findRecentByMemberIdAndStatus(member.getId(),
				ChatRoomStatus.ACTIVE.name(), ROOM_LIST_LIMIT)
			.stream()
			.map(ChatRoomResponse::from)
			.toList();
	}

	public List<ChatMessageHistoryResponse> findMessages(Member member, Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		List<ChatMessageHistoryResponse> recentMessages = new ArrayList<>(chatMessageRepository
			.findRecentByChatRoomIdOrderByIdDesc(chatRoom.getId(), MESSAGE_HISTORY_LIMIT)
			.stream()
			.map(ChatMessageHistoryResponse::from)
			.toList());
		Collections.reverse(recentMessages);
		return recentMessages;
	}

	@Transactional
	public void archive(Member member, Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		chatRoom.archive();
		fileAssetService.deleteFilesForChatRoom(chatRoom.getId());
		maskingService.clearMappings(chatRoom.getId());
	}

	/**
	 * 마스킹 확인 전용으로 만들어졌지만 아직 승인되지 않은 빈 채팅방을 폐기합니다.
	 *
	 * <p>메시지가 한 건이라도 저장된 방은 정상 대화방이므로 삭제하지 않습니다. 이 조건을
	 * 서비스에서 다시 확인해야 늦게 도착한 취소 요청이 승인 완료된 대화를 지우지 않습니다.
	 */
	@Transactional
	public void discardEmptyPreview(Member member, Long chatRoomId) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
		if (chatMessageRepository.existsByChatRoom_Id(chatRoom.getId())) {
			return;
		}
		maskingService.clearMappings(chatRoom.getId());
		chatRoomRepository.delete(chatRoom);
	}
}
