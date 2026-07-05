package haitai.safemask.domain.chatmessage.dto;

import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 화면 미리보기용 탐지 정보입니다.
 *
 * <p>보안상 원본값은 내려주지 않습니다. 화면은 원문(content)과 위치 정보로
 * 하이라이트를 만들고, 서버는 감사 기록에 원본 없이 token/type/위치만 저장합니다.
 */
public record MaskingDetectionResponse(
	MaskingType type,
	String displayName,
	String token,
	int startIndex,
	int endIndex
) {
	public static MaskingDetectionResponse from(Detection detection) {
		return new MaskingDetectionResponse(
			detection.type(),
			detection.type().getDisplayName(),
			detection.token(),
			detection.startIndex(),
			detection.endIndex()
		);
	}
}
