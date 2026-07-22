package haitai.safemask.domain.maskingrule.enums;

/** 관리자가 등록할 수 있는 사용자 정의 마스킹 규칙의 탐지 방식입니다. */
public enum MaskingRuleKind {
	/** 입력한 값을 정규식 문법이 아닌 일반 문자열 그대로 탐지합니다. */
	KEYWORD,
	/** 안전성 검사를 통과한 제한된 Java 정규식으로 탐지합니다. */
	REGEX
}
