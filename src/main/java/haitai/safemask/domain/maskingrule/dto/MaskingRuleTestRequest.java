package haitai.safemask.domain.maskingrule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaskingRuleTestRequest(
	@NotNull(message = "규칙 버전은 필수입니다.")
	Long version,

	@NotBlank(message = "테스트 원문은 필수입니다.")
	@Size(max = 10000, message = "테스트 원문은 10,000자 이하여야 합니다.")
	String sampleText
) {
}
