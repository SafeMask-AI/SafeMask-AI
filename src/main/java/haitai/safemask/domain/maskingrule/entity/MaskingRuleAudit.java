package haitai.safemask.domain.maskingrule.entity;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleAuditAction;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 사용자 정의 마스킹 규칙의 변경 당시 값을 보존하는 감사 스냅샷입니다.
 * 규칙이 이후 다시 수정되어도 당시 적용값·작업자·사유를 확인할 수 있습니다.
 */
@Getter
@Entity
@Table(name = "MASK_MASKING_RULE_AUDIT")
public class MaskingRuleAudit {

	@Id
	@SequenceGenerator(name = "masking_rule_audit_seq_gen",
		sequenceName = "SAFEMASK_MASK_RULE_AUDIT_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "masking_rule_audit_seq_gen")
	private Long id;

	@Column(nullable = false)
	private Long maskingRuleId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingRuleAuditAction action;

	@Column(nullable = false, length = 100)
	private String ruleName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MaskingType maskingType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingRuleKind ruleKind;

	@Column(nullable = false, length = 1000)
	private String pattern;

	@Column(nullable = false)
	private Integer priority;

	@Column(nullable = false)
	private Boolean enabled;

	@Column(length = 500)
	private String description;

	@Column(nullable = false)
	private Long ruleVersion;

	@Column(nullable = false)
	private Long changedByMemberId;

	@Column(nullable = false, length = 50)
	private String changedByName;

	@Column(nullable = false, length = 300)
	private String changeReason;

	@Column(nullable = false, updatable = false)
	private LocalDateTime changedAt;

	protected MaskingRuleAudit() {
	}

	public static MaskingRuleAudit snapshot(MaskingRule rule, MaskingRuleAuditAction action,
		Long administratorId, String administratorName, String reason) {
		MaskingRuleAudit audit = new MaskingRuleAudit();
		audit.maskingRuleId = rule.getId();
		audit.action = action;
		audit.ruleName = rule.getName();
		audit.maskingType = rule.getType();
		audit.ruleKind = rule.getRuleKind();
		audit.pattern = rule.getPattern();
		audit.priority = rule.getPriority();
		audit.enabled = rule.getEnabled();
		audit.description = rule.getDescription();
		audit.ruleVersion = rule.getVersion();
		audit.changedByMemberId = administratorId;
		audit.changedByName = administratorName;
		audit.changeReason = reason;
		return audit;
	}

	@PrePersist
	void prePersist() {
		this.changedAt = LocalDateTime.now();
	}
}
