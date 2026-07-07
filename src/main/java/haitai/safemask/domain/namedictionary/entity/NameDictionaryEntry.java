package haitai.safemask.domain.namedictionary.entity;

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
 * 이름 사전 항목입니다. (성을 뺀 이름 부분, 예: "민준", "수정")
 *
 * <p>이름 탐지 휴리스틱만으로는 "고마워" 같은 일반 단어와 "김수정" 같은 실제 성명을
 * 구분할 수 없어, "성씨 + 사전에 등록된 이름" 조합일 때만 이름으로 판정하기 위한
 * 근거 데이터입니다. 기본 데이터는 공개 인명 통계 기반 시드 파일로 적재되며,
 * 사내에서 자주 쓰는 이름은 관리자가 직접 추가할 수 있습니다.
 */
@Getter
@Entity
@Table(name = "MASK_NAME_DICT")
public class NameDictionaryEntry {

	@Id
	@SequenceGenerator(name = "name_dict_seq_gen", sequenceName = "SAFEMASK_NAME_DICT_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "name_dict_seq_gen")
	private Long id;

	/** 성을 뺀 이름 (예: "민준"). 탐지 시 성씨 1자와 조합해 성명 여부를 판정 */
	@Column(name = "given_name", nullable = false, unique = true, length = 10)
	private String givenName;

	/**
	 * 등록 출처. 시더가 SEED 출처 항목을 시드 파일과 "동기화"(파일에서 빠진 이름은 삭제)
	 * 하기 위한 구분값입니다. CUSTOM(관리자 수동 등록)은 시더가 절대 건드리지 않습니다.
	 * 컬럼 추가 이전에 들어간 과거 행은 NULL이며 SEED로 취급합니다. (isSeedOrigin 참고)
	 */
	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private Source source;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public enum Source {
		/** 시드 파일에서 자동 적재 — 시드 파일과 동기화 대상 */
		SEED,
		/** 관리자가 직접 등록 — 시더가 건드리지 않음 */
		CUSTOM
	}

	@PrePersist
	void onCreate() {
		this.createdAt = LocalDateTime.now();
	}

	/** 시드 출처 여부. 컬럼 도입 전의 NULL 행도 시드 출처로 간주해 동기화 대상에 포함합니다 */
	public boolean isSeedOrigin() {
		return source == null || source == Source.SEED;
	}

	public static NameDictionaryEntry createSeed(String givenName) {
		NameDictionaryEntry entry = new NameDictionaryEntry();
		entry.givenName = givenName;
		entry.source = Source.SEED;
		return entry;
	}
}
