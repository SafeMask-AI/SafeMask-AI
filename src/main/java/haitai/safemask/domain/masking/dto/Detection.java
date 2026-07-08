package haitai.safemask.domain.masking.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 텍스트에서 탐지된 민감정보 1건입니다.
 *
 * <p>주의: originalValue는 화면 미리보기(하이라이트)와 Redis 매핑 등록에만 사용하고,
 * DB(MaskingEntity)에는 절대 저장하지 않습니다. DB에는 type/token/위치만 남깁니다.
 *
 * @param type          민감정보 종류
 * @param originalValue 탐지된 원본값 (사내 전용, DB 저장 금지)
 * @param token         원본값을 대체한 마스킹 토큰 (예: [PHONE_001])
 * @param startIndex    원문 기준 탐지 시작 위치 (미리보기 하이라이트용)
 * @param endIndex      원문 기준 탐지 끝 위치 (exclusive)
 * @param ruleName      탐지를 만든 규칙 이름 (화면 상세 설명용, 원본값 아님)
 */
public record Detection(
	MaskingType type,
	String originalValue,
	String token,
	int startIndex,
	int endIndex,
	String ruleName
) {
}
