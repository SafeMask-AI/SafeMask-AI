package haitai.safemask.domain.maskingrule.dto;

import haitai.safemask.domain.maskingrule.entity.MaskingRuleAudit;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleAuditAction;
import java.time.LocalDateTime;

public record MaskingRuleAuditResponse(
	Long id,
	MaskingRuleAuditAction action,
	String ruleName,
	boolean enabled,
	Long ruleVersion,
	String changedByName,
	String changeReason,
	LocalDateTime changedAt
) {
	public static MaskingRuleAuditResponse from(MaskingRuleAudit audit) {
		return new MaskingRuleAuditResponse(audit.getId(), audit.getAction(), audit.getRuleName(),
			Boolean.TRUE.equals(audit.getEnabled()), audit.getRuleVersion(), audit.getChangedByName(),
			audit.getChangeReason(), audit.getChangedAt());
	}
}
