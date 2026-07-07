package haitai.safemask.domain.chatmessage.dto;

/**
 * 답변 재생성 요청. 대상 채팅방만 지정하면 서버가 마지막 AI 답변을 찾아 다시 생성합니다.
 *
 * @param chatRoomId 재생성할 채팅방 ID
 */
public record ChatRegenerateRequest(
	Long chatRoomId
) {
}
