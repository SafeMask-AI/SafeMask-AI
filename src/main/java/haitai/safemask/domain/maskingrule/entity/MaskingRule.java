package haitai.safemask.domain.maskingrule.entity;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
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
import java.time.LocalDateTime;
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
@Table(name = "MASKING_RULE")
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

	/** 탐지용 정규식 패턴 */
	@Column(nullable = false, length = 1000)
	private String pattern;

	/** 적용 우선순위 (숫자가 낮을수록 먼저 적용) */
	@Column(nullable = false)
	private Integer priority;

	/** 활성화 여부 (false면 탐지에서 제외) */
	@Column(nullable = false)
	private Boolean enabled;

	/** 규칙 설명 (관리자 참고용) */
	@Column(length = 500)
	private String description;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	protected MaskingRule() {
	}

	/**
	 * 새 마스킹 규칙을 생성합니다. (기본 시드 등록, 관리자 규칙 추가에서 사용)
	 *
	 * 정규식 유효성은 여기서 검증하지 않습니다.
	 * 잘못된 패턴이 저장되더라도 마스킹 엔진이 컴파일 실패 규칙을 건너뛰고
	 * 로그로 남기므로, 규칙 하나가 깨져도 전체 마스킹이 중단되지 않습니다.
	 * (등록 화면에서의 사전 검증은 관리자 API 계층에서 담당)
	 */
	public static MaskingRule create(String name, MaskingType type, String pattern, Integer priority,
		String description) {
		MaskingRule rule = new MaskingRule();
		rule.name = name;
		rule.type = type;
		rule.pattern = pattern;
		rule.priority = priority;
		rule.enabled = true;
		rule.description = description;
		return rule;
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;

		if (this.enabled == null) {
			this.enabled = true;
		}
		if (this.priority == null) {
			this.priority = 100;
		}
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
