package haitai.safemask.domain.masking.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 텍스트에서 탐지된 민감정보 1건입니다.
 *
	 * <p>주의: originalValue는 화면 미리보기, 단기 승인 스냅샷과 Redis 원복 매핑에만 사용합니다.
	 * MaskingEntity에는 type/token/위치만 저장하며, 최종 전송이 확정되면 전체 원문은 사내
	 * ChatMessage.originalContent에 별도로 보관됩니다.
 *
 * @param type          민감정보 종류
	 * @param originalValue 탐지된 원본값 (사내 처리 전용, MaskingEntity 저장 금지)
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
