package haitai.safemask.domain.maskingrule.dto;

import haitai.safemask.domain.masking.dto.Detection;

public record MaskingRuleDetectionResponse(
	String originalValue,
	String token,
	int startIndex,
	int endIndex
) {
	public static MaskingRuleDetectionResponse from(Detection detection) {
		return new MaskingRuleDetectionResponse(detection.originalValue(), detection.token(),
			detection.startIndex(), detection.endIndex());
	}
}
