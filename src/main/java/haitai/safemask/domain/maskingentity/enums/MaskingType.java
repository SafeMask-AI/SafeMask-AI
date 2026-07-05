package haitai.safemask.domain.maskingentity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 탐지 대상 민감정보의 종류입니다.
 * MaskingEntity(탐지 결과)와 MaskingRule(탐지 규칙)에서 공통으로 사용합니다.
 *
 * <p>displayName은 마스킹 요약 화면("이름 3건, 전화번호 2건")처럼
 * 사용자에게 유형을 보여줄 때 사용합니다.
 */
@Getter
@RequiredArgsConstructor
public enum MaskingType {

	/** 사람 이름 */
	NAME("이름", "PERSON"),

	/** 전화번호 (휴대폰·유선) */
	PHONE("전화번호", "PHONE"),

	/** 이메일 주소 */
	EMAIL("이메일", "EMAIL"),

	/** 주민등록번호 */
	RRN("주민등록번호", "RRN"),

	/** 신용카드 번호 */
	CARD_NUMBER("카드번호", "CARD"),

	/** 계좌번호 */
	ACCOUNT_NUMBER("계좌번호", "ACCOUNT"),

	/** 주소 */
	ADDRESS("주소", "ADDRESS"),

	/** 사번 */
	EMPLOYEE_NO("사번", "EMPLOYEE"),

	/** 내부망 서버·장비의 IP 주소 */
	IP("IP 주소", "IP"),

	/** 관리자가 등록한 커스텀 규칙 (프로젝트 코드명, 거래처명 등) */
	CUSTOM("사용자 지정", "CUSTOM");

	/** 화면 표시용 한국어 이름 */
	private final String displayName;

	/** GPT에 전달되는 마스킹 토큰의 유형 라벨입니다. 예: [PERSON_001] */
	private final String tokenLabel;
}
