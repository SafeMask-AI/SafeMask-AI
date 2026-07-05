package haitai.safemask.domain.chatmessage.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 사용자가 마스킹 미리보기에서 직접 추가 지정한 값입니다.
 *
 * <p>value는 서버 내부에서만 토큰 매핑에 사용하고, 응답이나 감사 DB에는 저장하지 않습니다.
 */
public record ManualMaskRequest(
	String value,
	MaskingType type
) {
}
