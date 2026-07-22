package haitai.safemask.domain.maskingrule.entity;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleOrigin;
import haitai.safemask.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import lombok.Getter;

/**
 * 민감정보를 탐지하는 규칙입니다.
 * 관리자(ADMIN)가 등록·수정하며, 마스킹 엔진이 활성화된 규칙을
 * 우선순위 순으로 적용해 텍스트에서 민감정보를 찾아냅니다.
 *
 * <p>예: type=PHONE, pattern="01[016789]-?\\d{3,4}-?\\d{4}" 규칙이 매칭되면
 * 해당 구간이 MaskingEntity로 기록되고 토큰으로 치환됩니다.
 */
@Getter
@Entity
@Table(name = "MASK_MASKING_RULE")
public class MaskingRule {

	@Id
	@SequenceGenerator(name = "masking_rule_seq_gen", sequenceName = "SAFEMASK_MASKING_RULE_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "masking_rule_seq_gen")
	private Long id;

	/** 규칙 이름 (관리 화면 표시용, 예: "휴대폰 번호") */
	@Column(nullable = false, unique = true, length = 100)
	private String name;

	/** 이 규칙이 탐지하는 민감정보 종류 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MaskingType type;

	/** REGEX 규칙의 정규식 또는 KEYWORD 규칙의 리터럴 탐지값 */
	@Column(nullable = false, length = 1000)
	private String pattern;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingRuleKind ruleKind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MaskingRuleOrigin origin;

	/** 적용 우선순위 (숫자가 낮을수록 먼저 적용) */
	@Column(nullable = false)
	private Integer priority;

	/** 활성화 여부 (false면 탐지에서 제외) */
	@Column(nullable = false)
	private Boolean enabled;

	/** 규칙 설명 (관리자 참고용) */
	@Column(length = 500)
	private String description;

	private Long createdByMemberId;

	@Column(length = 50)
	private String createdByName;

	private Long updatedByMemberId;

	@Column(length = 50)
	private String updatedByName;

	private LocalDateTime lastTestedAt;

	@Column(length = 64)
	private String lastTestedFingerprint;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	protected MaskingRule() {
	}

	/**
	 * 코드가 소유하는 필수 시스템 규칙을 생성합니다.
	 * 시스템 규칙은 시더만 생성·갱신하며 관리자 API에서 수정하거나 끌 수 없습니다.
	 */
	public static MaskingRule create(String name, MaskingType type, String pattern, Integer priority,
		String description) {
		MaskingRule rule = new MaskingRule();
		rule.name = name;
		rule.type = type;
		rule.pattern = pattern;
		rule.ruleKind = MaskingRuleKind.REGEX;
		rule.origin = MaskingRuleOrigin.SYSTEM;
		rule.priority = priority;
		rule.enabled = true;
		rule.description = description;
		return rule;
	}

	/** 관리자 입력 검증이 끝난 사용자 정의 규칙을 비활성 초안으로 생성합니다. */
	public static MaskingRule createCustom(String name, MaskingType type, MaskingRuleKind ruleKind,
		String pattern, Integer priority, String description, Member administrator) {
		MaskingRule rule = new MaskingRule();
		rule.name = name;
		rule.type = type;
		rule.ruleKind = ruleKind;
		rule.origin = MaskingRuleOrigin.CUSTOM;
		rule.pattern = pattern;
		rule.priority = priority;
		rule.description = description;
		rule.enabled = false;
		rule.createdByMemberId = administrator.getId();
		rule.createdByName = administrator.getName();
		rule.updatedByMemberId = administrator.getId();
		rule.updatedByName = administrator.getName();
		return rule;
	}

	/**
	 * 기본 규칙 시더가 코드에 정의된 최신 보호 기준으로 규칙을 갱신합니다.
	 * 필수 규칙은 설정 실수로 비활성화되어도 재기동 시 반드시 복구합니다.
	 */
	public void applySeedDefaults(MaskingType type, String pattern, Integer priority, String description) {
		this.type = type;
		this.pattern = pattern;
		this.priority = priority;
		this.description = description;
		this.ruleKind = MaskingRuleKind.REGEX;
		this.origin = MaskingRuleOrigin.SYSTEM;
		this.enabled = true;
	}

	public void updateCustom(String name, MaskingType type, MaskingRuleKind ruleKind, String pattern,
		Integer priority, String description, Member administrator) {
		assertCustom();
		this.name = name;
		this.type = type;
		this.ruleKind = ruleKind;
		this.pattern = pattern;
		this.priority = priority;
		this.description = description;
		this.enabled = false;
		this.lastTestedAt = null;
		this.lastTestedFingerprint = null;
		markUpdatedBy(administrator);
	}

	public void activate(Member administrator) {
		assertCustom();
		if (!isTestedCurrentDefinition()) {
			throw new IllegalStateException("현재 규칙 정의가 테스트되지 않았습니다.");
		}
		this.enabled = true;
		markUpdatedBy(administrator);
	}

	public void deactivate(Member administrator) {
		assertCustom();
		this.enabled = false;
		markUpdatedBy(administrator);
	}

	/** KEYWORD는 정규식 메타문자를 해석하지 않고 입력 문자열 그대로 탐지합니다. */
	public String patternForCompilation() {
		return ruleKind == MaskingRuleKind.KEYWORD ? java.util.regex.Pattern.quote(pattern) : pattern;
	}

	public boolean isSystemRule() {
		return origin == MaskingRuleOrigin.SYSTEM;
	}

	/** 현재 탐지 정의가 테스트된 사실을 지문으로 기록해 이후 수정과 구분합니다. */
	public void markTested() {
		assertCustom();
		this.lastTestedAt = LocalDateTime.now();
		this.lastTestedFingerprint = definitionFingerprint();
	}

	public boolean isTestedCurrentDefinition() {
		return lastTestedFingerprint != null && lastTestedFingerprint.equals(definitionFingerprint());
	}

	private String definitionFingerprint() {
		String definition = type.name() + '\u0000' + ruleKind.name() + '\u0000' + pattern;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(definition.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
		}
	}

	private void assertCustom() {
		if (isSystemRule()) {
			throw new IllegalStateException("시스템 마스킹 규칙은 관리자 변경 대상이 아닙니다.");
		}
	}

	private void markUpdatedBy(Member administrator) {
		this.updatedByMemberId = administrator.getId();
		this.updatedByName = administrator.getName();
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;

		if (this.enabled == null) {
			this.enabled = this.origin == MaskingRuleOrigin.SYSTEM;
		}
		if (this.priority == null) {
			this.priority = 100;
		}
		if (this.ruleKind == null) {
			this.ruleKind = MaskingRuleKind.REGEX;
		}
		if (this.origin == null) {
			this.origin = MaskingRuleOrigin.SYSTEM;
		}
		if (this.version == null) {
			this.version = 0L;
		}
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
