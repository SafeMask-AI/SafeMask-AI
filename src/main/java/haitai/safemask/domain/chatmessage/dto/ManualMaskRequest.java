package haitai.safemask.domain.chatmessage.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 사용자가 마스킹 미리보기에서 직접 추가 지정한 값입니다.
 *
	 * <p>value는 서버 내부의 원복 매핑과 단기 승인 스냅샷에 사용합니다.
	 * 외부 AI에는 토큰만 전달하며 MaskingEntity 감사 기록에도 원본값은 저장하지 않습니다.
 */
public record ManualMaskRequest(
	String value,
	MaskingType type
) {
}
