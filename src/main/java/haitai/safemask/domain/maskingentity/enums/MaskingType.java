package haitai.safemask.domain.maskingentity.enums;

/**
 * 탐지 대상 민감정보의 종류입니다.
 * MaskingEntity(탐지 결과)와 MaskingRule(탐지 규칙)에서 공통으로 사용합니다.
 */
public enum MaskingType {

	/** 사람 이름 */
	NAME,

	/** 전화번호 (휴대폰·유선) */
	PHONE,

	/** 이메일 주소 */
	EMAIL,

	/** 주민등록번호 */
	RRN,

	/** 신용카드 번호 */
	CARD_NUMBER,

	/** 계좌번호 */
	ACCOUNT_NUMBER,

	/** 주소 */
	ADDRESS,

	/** 사번 */
	EMPLOYEE_NO,

	/** 관리자가 등록한 커스텀 규칙 (프로젝트 코드명, 거래처명 등) */
	CUSTOM
}
