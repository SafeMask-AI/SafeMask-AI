package haitai.safemask.domain.airun.enums;

/**
 * AI 실행 파이프라인의 진행 단계입니다.
 *
 * <p>정상 흐름: PENDING → PREVIEW → APPROVED → CALLING → COMPLETED
 * <br>사용자가 미리보기에서 거부하면 REJECTED, 진행 중 중단하면 CANCELLED,
 * 중간에 오류가 나면 FAILED로 종료됩니다.
 */
public enum AiRunStatus {

	/** 요청 접수, 민감정보 탐지 진행 중 */
	PENDING,

	/** 탐지 완료, 마스킹 미리보기 표시 후 사용자 승인 대기 */
	PREVIEW,

	/** 사용자가 마스킹 결과를 확인하고 승인함 */
	APPROVED,

	/** 사용자가 마스킹 미리보기에서 전송을 거부함 (GPT로 아무것도 전송되지 않음) */
	REJECTED,

	/** 마스킹된 데이터를 GPT API로 전송, 응답 대기 중 */
	CALLING,

	/** GPT 응답 수신 및 토큰 원복까지 완료 */
	COMPLETED,

	/** 사용자가 진행 중인 응답 생성을 명시적으로 중단함 */
	CANCELLED,

	/** 탐지·호출·원복 중 오류 발생 */
	FAILED
}
