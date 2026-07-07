package haitai.safemask.domain.namedictionary.service;

import haitai.safemask.domain.namedictionary.entity.NameDictionaryEntry;
import haitai.safemask.domain.namedictionary.repository.NameDictionaryRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이름 사전을 관리합니다: 기동 시 시드 파일을 DB에 적재하고, 전체를 메모리에 올려
 * 마스킹 파이프라인의 이름 판정("성씨+이름 조합인가?")에 사용합니다.
 *
 * <p>메모리 캐시를 쓰는 이유: 이름 판정은 채팅 한 건마다 여러 번 일어나는데,
 * 그때마다 DB를 조회하면 마스킹 지연이 채팅 반응성을 해칩니다. 사전은 수만 건
 * 이하의 짧은 문자열이라 메모리 Set으로 충분하고, 조회는 O(1)입니다.
 *
 * <p>시드 정책은 MaskingRuleSeeder와 동일합니다: 시드 파일에 있는데 DB에 없는
 * 이름만 추가하고, 관리자가 DB에 직접 추가한 이름은 건드리지 않습니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NameDictionaryService implements ApplicationRunner {

	/** 공개 인명 통계 기반으로 정리한 기본 이름 시드 파일 (성 제외, 한 줄에 하나) */
	private static final String SEED_RESOURCE = "/masking/korean-given-names.txt";

	private final NameDictionaryRepository nameDictionaryRepository;

	/**
	 * 이름 판정용 메모리 사전. 기동·갱신 시 통째로 교체하며(volatile),
	 * 교체 전까지는 이전 사전으로 판정하므로 요청 처리 중에도 안전합니다.
	 */
	private volatile Set<String> givenNames = Set.of();

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seedFromResource();
		reload();
	}

	/**
	 * 시드 출처 항목을 시드 파일과 완전히 "동기화"합니다:
	 * <ul>
	 *   <li>파일에 있는데 DB에 없는 이름 → 추가</li>
	 *   <li>SEED 출처인데 파일에서 빠진 이름 → 삭제 — 잘못된 시드가 배포됐어도
	 *       파일을 고치고 재기동하면 DB가 스스로 정리됩니다 (자정 기능)</li>
	 *   <li>CUSTOM(관리자 수동 등록) 항목 → 절대 건드리지 않음</li>
	 * </ul>
	 */
	private void seedFromResource() {
		List<String> seedNames = readSeedNames();
		if (seedNames.isEmpty()) {
			// 파일을 못 읽었을 때 동기화를 진행하면 SEED 전체가 삭제되므로 아무것도 하지 않는다
			log.warn("이름 사전 시드 파일이 비어 있거나 읽지 못했습니다: {}", SEED_RESOURCE);
			return;
		}
		Set<String> seedSet = new HashSet<>(seedNames);

		List<NameDictionaryEntry> allEntries = nameDictionaryRepository.findAll();
		Set<String> existingNames = allEntries.stream()
			.map(NameDictionaryEntry::getGivenName)
			.collect(Collectors.toSet());

		// 시드 출처(과거 NULL 포함)인데 현재 시드 파일에 없는 이름은 제거
		List<NameDictionaryEntry> stale = allEntries.stream()
			.filter(NameDictionaryEntry::isSeedOrigin)
			.filter(entry -> !seedSet.contains(entry.getGivenName()))
			.toList();
		nameDictionaryRepository.deleteAll(stale);

		List<NameDictionaryEntry> toInsert = seedNames.stream()
			.filter(seedName -> !existingNames.contains(seedName))
			.map(NameDictionaryEntry::createSeed)
			.toList();
		nameDictionaryRepository.saveAll(toInsert);

		log.info("이름 사전 동기화 완료: 시드 {}건 기준 신규 {}건 추가, 파일에서 빠진 {}건 제거 (총 {}건 유지)",
			seedNames.size(), toInsert.size(), stale.size(),
			existingNames.size() + toInsert.size() - stale.size());
	}

	private List<String> readSeedNames() {
		InputStream stream = getClass().getResourceAsStream(SEED_RESOURCE);
		if (stream == null) {
			return List.of();
		}

		List<String> names = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String name = line.trim();
				// 주석·빈 줄을 건너뛰고, 이름으로 성립하는 한글 1~4자만 받는다 (시드 파일 오염 방어)
				if (name.isEmpty() || name.startsWith("#") || !name.matches("[가-힣]{1,4}")) {
					continue;
				}
				if (seen.add(name)) {
					names.add(name);
				}
			}
		} catch (IOException e) {
			log.error("이름 사전 시드 파일을 읽지 못했습니다: {}", SEED_RESOURCE, e);
			return List.of();
		}
		return names;
	}

	/** DB의 이름 전체를 메모리 사전으로 다시 올립니다. (관리자 추가 반영 시에도 호출) */
	public void reload() {
		this.givenNames = nameDictionaryRepository.findAll().stream()
			.map(NameDictionaryEntry::getGivenName)
			.collect(Collectors.toUnmodifiableSet());
		log.info("이름 사전 메모리 적재 완료: {}건", givenNames.size());
	}

	/** 사전이 비어 있으면 사전 기반 이름 규칙을 쓸 수 없습니다. (MaskingService의 안전장치용) */
	public boolean isEmpty() {
		return givenNames.isEmpty();
	}

	/** 현재 메모리 사전 스냅샷 (불변 Set — 마스킹 판정 정책에 전달용) */
	public Set<String> snapshot() {
		return givenNames;
	}
}
