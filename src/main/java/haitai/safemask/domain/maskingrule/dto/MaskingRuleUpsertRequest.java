package haitai.safemask.domain.maskingrule.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaskingRuleUpsertRequest(
	@NotBlank(message = "규칙 이름은 필수입니다.")
	@Size(max = 100, message = "규칙 이름은 100자 이하여야 합니다.")
	String name,

	@NotNull(message = "마스킹 분류는 필수입니다.")
	MaskingType type,

	@NotNull(message = "탐지 방식은 필수입니다.")
	MaskingRuleKind ruleKind,

	@NotBlank(message = "탐지값은 필수입니다.")
	@Size(max = 500, message = "탐지값은 500자 이하여야 합니다.")
	String pattern,

	@NotNull(message = "우선순위는 필수입니다.")
	@Min(value = 1000, message = "사용자 정의 규칙 우선순위는 1000 이상이어야 합니다.")
	@Max(value = 9999, message = "사용자 정의 규칙 우선순위는 9999 이하여야 합니다.")
	Integer priority,

	@Size(max = 500, message = "설명은 500자 이하여야 합니다.")
	String description,

	@Size(max = 300, message = "변경 사유는 300자 이하여야 합니다.")
	@NotBlank(message = "변경 사유는 필수입니다.")
	String reason,

	Long version
) {
}
