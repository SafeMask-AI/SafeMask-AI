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

	/**
	 * 주민등록번호·외국인등록번호. 같은 형식(뒷자리 성별코드만 내국인 1~4, 외국인 5~8)이라
	 * 한 유형으로 탐지하며, 표시명에 둘을 함께 적어 건수 오해를 막습니다.
	 */
	RRN("주민/외국인등록번호", "RRN"),

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

	/** 여권번호 */
	PASSPORT("여권번호", "PASSPORT"),

	/** 운전면허번호 */
	DRIVER_LICENSE("운전면허번호", "LICENSE"),

	/** 차량번호 (자동차 등록번호판) */
	VEHICLE_NUMBER("차량번호", "VEHICLE"),

	/** 공시 전 재무·실적 수치 */
	FINANCIAL_RESULT("공시 전 재무/실적", "FINANCE"),

	/** 원가·단가·견적·입찰 등 가격 조건 */
	COST_PRICE("원가/가격 정보", "PRICE"),

	/** 계약 금액·기간·지급 조건 */
	CONTRACT_AMOUNT("계약/거래 조건", "CONTRACT"),

	/** 법무·소송·분쟁 문서 정보 */
	LEGAL_DOCUMENT("법무/소송 정보", "LEGAL"),

	/** 영업비밀·사업 전략 */
	TRADE_SECRET("영업비밀/전략", "TRADE_SECRET"),

	/** DB·SQL·서버·내부 URL 등 기술 식별자 */
	TECH_IDENTIFIER("기술/인프라 정보", "TECH"),

	/** SQL 테이블·컬럼·스키마 등 쿼리 식별자 */
	SQL_QUERY("SQL 쿼리 정보", "SQL"),

	/** API Key, 토큰, 비밀번호, 개인키 등 보안 시크릿 */
	SECURITY_SECRET("보안 시크릿", "SECRET"),

	/** 급여·연봉·성과급 등 인사 민감 금액 */
	HR_COMPENSATION("인사/급여 정보", "HR"),

	/** 고객사·거래처·고객 등급 등 거래처 민감정보 */
	CUSTOMER_ACCOUNT("고객/거래처 정보", "CUSTOMER"),

	/** 품의번호·결재번호·문서번호 등 내부 관리번호 */
	INTERNAL_DOC_ID("내부 문서/관리번호", "DOC_ID"),

	/** 관리자가 등록한 커스텀 규칙 (프로젝트 코드명, 거래처명 등) */
	CUSTOM("사용자 지정", "CUSTOM");

	/** 화면 표시용 한국어 이름 */
	private final String displayName;

	/** GPT에 전달되는 마스킹 토큰의 유형 라벨입니다. 예: [PERSON_001] */
	private final String tokenLabel;
}
