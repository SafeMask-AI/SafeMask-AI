package haitai.safemask.domain.maskingrule.dto;

import java.util.List;

public record MaskingRuleTestResponse(
	String maskedText,
	int detectionCount,
	List<MaskingRuleDetectionResponse> detections,
	Long version
) {
}
