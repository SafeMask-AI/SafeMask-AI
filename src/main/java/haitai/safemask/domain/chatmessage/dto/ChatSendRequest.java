package haitai.safemask.domain.chatmessage.dto;

import java.util.List;

/**
 * 채팅 메시지 전송 요청입니다.
 *
 * <p>approved=false이거나 생략된 상태에서 민감정보가 탐지되면 GPT 호출 없이
 * 미리보기 응답만 반환합니다. 클라이언트가 사용자의 확인을 받은 뒤 같은 content와
 * approved=true로 다시 호출하면 실제 GPT 전송이 진행됩니다.
 */
public record ChatSendRequest(
	Long chatRoomId,
	String content,
	Boolean approved,
	List<ManualMaskRequest> manualMasks
) {
	public boolean isApproved() {
		return Boolean.TRUE.equals(approved);
	}
}
