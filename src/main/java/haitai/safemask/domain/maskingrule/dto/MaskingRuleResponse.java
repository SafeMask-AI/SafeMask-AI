package haitai.safemask.domain.maskingrule.dto;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleOrigin;
import java.time.LocalDateTime;

public record MaskingRuleResponse(
	Long id,
	String name,
	MaskingType type,
	MaskingRuleKind ruleKind,
	MaskingRuleOrigin origin,
	String pattern,
	Integer priority,
	boolean enabled,
	String description,
	Long version,
	String updatedByName,
	boolean tested,
	LocalDateTime lastTestedAt,
	LocalDateTime createdAt,
	LocalDateTime updatedAt
) {
	public static MaskingRuleResponse from(MaskingRule rule) {
		return new MaskingRuleResponse(rule.getId(), rule.getName(), rule.getType(), rule.getRuleKind(),
			rule.getOrigin(), rule.getPattern(), rule.getPriority(), Boolean.TRUE.equals(rule.getEnabled()),
			rule.getDescription(), rule.getVersion(), rule.getUpdatedByName(), rule.isTestedCurrentDefinition(),
			rule.getLastTestedAt(), rule.getCreatedAt(), rule.getUpdatedAt());
	}
}
