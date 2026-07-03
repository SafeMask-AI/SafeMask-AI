package haitai.safemask.domain.chatmessage.enums;

/**
 * 채팅 메시지의 발화 주체입니다.
 */
public enum MessageRole {

	/** 사용자가 입력한 메시지 */
	USER,

	/** GPT가 생성한 응답 메시지 */
	ASSISTANT
}
