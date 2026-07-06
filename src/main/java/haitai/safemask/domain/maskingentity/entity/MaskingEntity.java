package haitai.safemask.domain.maskingentity.entity;

import haitai.safemask.domain.airun.entity.AiRun;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 한 번의 AI 실행(AiRun)에서 탐지된 민감정보 1건에 대한 감사(audit) 기록입니다.
 * "이 호출에서 어떤 종류의 민감정보가 몇 건, 어떤 토큰으로 치환되어 나갔는지"를 남깁니다.
 *
 * <p>주의: 원본값은 DB에 저장하지 않습니다.
 * "원본값 ↔ 토큰" 매핑은 채팅방 단위로 Redis에만 보관(TTL 적용)하며,
 * GPT 응답의 토큰을 원본으로 원복할 때 Redis 매핑을 사용합니다.
 * 원복이 끝난 결과는 ChatMessage.originalContent에 저장되므로
 * 세션이 끝나면 원본 매핑은 어디에도 남지 않습니다. (개인정보 최소보관)
 */
@Getter
@Entity
@Table(name = "MASK_MASKING_ENTITY")
public class MaskingEntity {

	@Id
	@SequenceGenerator(name = "masking_entity_seq_gen", sequenceName = "SAFEMASK_MASKING_ENTITY_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "masking_entity_seq_gen")
	private Long id;

	/** 이 민감정보가 탐지된 AI 실행 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ai_run_id", nullable = false)
	private AiRun aiRun;

	/** 민감정보 종류 (이름, 전화번호, 주민번호 등) */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private MaskingType type;

	/** 원본값을 대체한 마스킹 토큰 (예: [PERSON_001]) — GPT로 나가는 값 */
	@Column(nullable = false, length = 100)
	private String maskedToken;

	/** 원문 내 탐지 시작 위치 (미리보기 하이라이트용) */
	@Column
	private Integer startIndex;

	/** 원문 내 탐지 끝 위치 (미리보기 하이라이트용) */
	@Column
	private Integer endIndex;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected MaskingEntity() {
	}

	public static MaskingEntity create(AiRun aiRun, MaskingType type, String maskedToken,
		Integer startIndex, Integer endIndex) {
		MaskingEntity maskingEntity = new MaskingEntity();
		maskingEntity.aiRun = aiRun;
		maskingEntity.type = type;
		maskingEntity.maskedToken = maskedToken;
		maskingEntity.startIndex = startIndex;
		maskingEntity.endIndex = endIndex;
		return maskingEntity;
	}

	@PrePersist
	void prePersist() {
		this.createdAt = LocalDateTime.now();
	}
}
