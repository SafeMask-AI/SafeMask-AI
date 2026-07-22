package haitai.safemask.domain.maskingrule.enums;

/** 마스킹 규칙의 관리 주체를 구분합니다. */
public enum MaskingRuleOrigin {
	/** 코드 배포로 관리되는 필수 보호 규칙이며 관리자 변경 대상이 아닙니다. */
	SYSTEM,
	/** 관리자가 코드 변경 없이 추가하는 사내 업무 규칙입니다. */
	CUSTOM
}
