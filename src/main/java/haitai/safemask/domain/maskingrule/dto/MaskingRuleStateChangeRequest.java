package haitai.safemask.domain.maskingrule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MaskingRuleStateChangeRequest(
	@NotNull(message = "규칙 버전은 필수입니다.")
	Long version,

	@NotBlank(message = "변경 사유는 필수입니다.")
	@Size(max = 300, message = "변경 사유는 300자 이하여야 합니다.")
	String reason
) {
}
