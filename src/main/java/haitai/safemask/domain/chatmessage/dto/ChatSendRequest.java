package haitai.safemask.domain.chatmessage.dto;

import java.util.List;

/**
 * 채팅 메시지 전송 요청입니다.
 *
 * <p>일반 요청은 content와 manualMasks를 검사해 민감정보가 있을 때만 미리보기를 반환합니다.
 * 사용자가 미리보기를 확인한 뒤에는 원문을 다시 신뢰하지 않고, 서버가 발급한 approvalId로
 * 승인 스냅샷을 소비해 실제 AI 전송을 진행합니다.
 */
public record ChatSendRequest(
	Long chatRoomId,
	String content,
	String approvalId,
	List<ManualMaskRequest> manualMasks
) {
	public boolean hasApprovalId() {
		return approvalId != null && !approvalId.isBlank();
	}
}
