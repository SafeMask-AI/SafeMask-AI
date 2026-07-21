package haitai.safemask.domain.chatroom.service;

import haitai.safemask.domain.chatmessage.approval.MaskingApprovalService;
import haitai.safemask.domain.chatmessage.repository.ChatMessageRepository;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.masking.config.MaskingProperties;
import haitai.safemask.domain.masking.service.MaskingService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 브라우저 종료 등으로 취소 요청을 받지 못한 만료 미리보기의 서버 흔적을 정리합니다. */
@Service
@RequiredArgsConstructor
public class ExpiredPreviewCleanupService {

	private static final int CLEANUP_BATCH_SIZE = 100;
	private static final Duration EXPIRY_GRACE = Duration.ofMinutes(5);

	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final MaskingApprovalService maskingApprovalService;
	private final MaskingService maskingService;
	private final MaskingProperties maskingProperties;

	/**
	 * 승인 TTL에 경계 경합 방지 여유시간을 더한 뒤 빈 방만 제한된 배치로 삭제합니다.
	 * 실행 직전에 메시지 존재 여부를 다시 확인해, 승인 완료와 정리가 겹쳐도 정상 대화는 보존합니다.
	 */
	@Scheduled(fixedDelayString = "${safemask.masking.preview-cleanup-interval:5m}",
		initialDelayString = "${safemask.masking.preview-cleanup-interval:5m}")
	@Transactional
	public void cleanup() {
		LocalDateTime cutoff = LocalDateTime.now()
			.minus(maskingProperties.getApprovalTtl())
			.minus(EXPIRY_GRACE);
		List<ChatRoom> expiredRooms = chatRoomRepository.findExpiredEmptyRooms(
			ChatRoomStatus.ACTIVE.name(), cutoff, CLEANUP_BATCH_SIZE);

		for (ChatRoom chatRoom : expiredRooms) {
			if (chatMessageRepository.existsByChatRoom_Id(chatRoom.getId())) {
				continue;
			}
			maskingApprovalService.discardForRoom(chatRoom.getMember(), chatRoom.getId());
			maskingService.clearMappings(chatRoom.getId());
			chatRoomRepository.delete(chatRoom);
		}
	}
}
