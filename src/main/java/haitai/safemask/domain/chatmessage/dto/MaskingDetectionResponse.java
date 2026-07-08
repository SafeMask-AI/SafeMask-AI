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
	String detailName,
	String token,
	int startIndex,
	int endIndex
) {
	public static MaskingDetectionResponse from(Detection detection) {
		return new MaskingDetectionResponse(
			detection.type(),
			detection.type().getDisplayName(),
			normalizeDetailName(detection.type(), detection.ruleName()),
			detection.token(),
			detection.startIndex(),
			detection.endIndex()
		);
	}

	private static String normalizeDetailName(MaskingType type, String detailName) {
		if (type != MaskingType.SQL_QUERY) {
			return detailName;
		}
		return switch (detailName) {
			case "SQL FROM 대상", "SQL 테이블명", "SQL 테이블명(FROM 목록)" -> "SQL 테이블명(FROM)";
			case "SQL JOIN 대상" -> "SQL 테이블명(JOIN)";
			case "SQL UPDATE 대상" -> "SQL 테이블명(UPDATE)";
			case "SQL 한정 식별자" -> "SQL 컬럼/스키마 식별자";
			default -> detailName;
		};
	}
}
