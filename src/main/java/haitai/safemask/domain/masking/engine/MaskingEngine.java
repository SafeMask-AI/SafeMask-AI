package haitai.safemask.domain.masking.engine;

import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 텍스트에서 민감정보를 탐지해 토큰으로 치환하고(마스킹),
 * GPT 응답의 토큰을 원본값으로 되돌리는(원복) 순수 엔진입니다.
 *
 * <p>설계 원칙:
 * <ul>
 *   <li>DB·Redis 등 외부 시스템에 직접 의존하지 않습니다.
 *       규칙은 파라미터로 받고, 토큰 발급/원복은 함수형 인터페이스
 *       (TokenAssigner/TokenResolver)로 주입받아 단위 테스트가 쉽습니다.</li>
 *   <li>탐지는 원문 기준 1회 스캔으로 끝냅니다. 치환된 결과를 다시 스캔하지 않으므로
 *       토큰 내부 문자열([PHONE_001]의 숫자 등)이 다른 규칙에 재탐지되는 일이 없습니다.</li>
 *   <li>우선순위가 앞선 규칙이 텍스트 구간을 선점합니다. 이후 규칙의 매칭이
 *       이미 선점된 구간과 겹치면 버립니다. (예: 주민번호 안의 숫자열이
 *       전화번호 규칙에 이중 매칭되는 것 방지)</li>
 * </ul>
 */
@Slf4j
@Component
public class MaskingEngine {

	/**
	 * 텍스트 속 마스킹 토큰을 찾는 패턴 (예: [PERSON_001], [PHONE_002]).
	 * 원복 시 GPT 응답에서 이 패턴에 걸리는 부분만 Redis 매핑으로 되돌립니다.
	 */
	private static final Pattern TOKEN_PATTERN = Pattern.compile(
		"\\[[A-Z_]+_\\d{3}]"
			+ "|\\b[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*\\b"
			+ "|\\b[A-Z][A-Z0-9_]*\\.\\*"
			+ "|\\bT\\d{2}_[A-Z][A-Z0-9_]*\\b"
			+ "|\\b(?:SEQ|OBJ|PKG|PRC|IDX)_[A-Z][A-Z0-9_]*\\b"
			+ "|\\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\\b");
	private static final Pattern SQL_QUALIFIED_SEMANTIC_TOKEN = Pattern.compile(
		"\\b([A-Z][A-Z0-9_]*)\\.([A-Z][A-Z0-9_]*)\\b");

	/**
	 * 규칙 정규식의 컴파일 결과 캐시. (패턴 문자열 → 컴파일 결과)
	 * 규칙 수가 적고 요청마다 재사용되므로 크기 제한 없이 캐시해도 안전합니다.
	 * 컴파일에 실패한 패턴은 empty로 캐시해 요청마다 재시도·로그 반복을 막습니다.
	 */
	private final Map<String, Optional<Pattern>> patternCache = new ConcurrentHashMap<>();

	/**
	 * 규칙이 찾은 매칭을 최종 탐지로 인정할지 판정하는 필터입니다.
	 *
	 * <p>정규식만으로 표현할 수 없는 판정(이름 후보의 사전 검증, 카드번호 체크섬,
	 * 매칭 앞 문맥 확인 등)을 호출부가 주입할 수 있게 합니다.
	 * 엔진은 사전·DB를 모르는 순수 구조를 유지합니다.
	 */
	@FunctionalInterface
	public interface DetectionFilter {

		/**
		 * true를 반환한 매칭만 탐지로 확정됩니다.
		 *
		 * @param start 원문에서 매칭이 시작되는 인덱스 (앞 문맥 검사용)
		 */
		boolean accept(MaskingRule rule, String value, int start);
	}

	/** 필터 없이 모든 매칭을 인정하는 기본 동작 (필터가 필요 없는 호출부·테스트용) */
	public MaskingResult mask(String text, List<MaskingRule> rules, TokenAssigner tokenAssigner) {
		return mask(text, rules, tokenAssigner, (rule, value, start) -> true);
	}

	/**
	 * 활성 규칙들을 우선순위 순으로 적용해 텍스트를 마스킹합니다.
	 *
	 * @param text            마스킹할 원문
	 * @param rules           적용할 규칙 (우선순위 오름차순으로 정렬된 상태여야 함)
	 * @param tokenAssigner   토큰 발급기 (운영: Redis, 테스트: 인메모리)
	 * @param detectionFilter 매칭 확정 전 최종 판정 필터 (예: 이름 사전 검증)
	 */
	public MaskingResult mask(String text, List<MaskingRule> rules, TokenAssigner tokenAssigner,
		DetectionFilter detectionFilter) {
		List<Detection> detections = new ArrayList<>();
		boolean[] occupied = new boolean[text.length()];

		for (MaskingRule rule : rules) {
			if (rule.getType() == MaskingType.SQL_QUERY) {
				continue;
			}
			Pattern pattern = compileOrSkip(rule);
			if (pattern == null) {
				continue;
			}

			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				int matchStart = sensitiveGroupStart(matcher);
				int matchEnd = sensitiveGroupEnd(matcher);
				// 빈 문자열 매칭 방어: 잘못 등록된 규칙(예: ".*?")이 위치마다 무의미한 토큰을 만드는 것 방지
				if (matchStart == matchEnd) {
					continue;
				}
				if (overlapsAccepted(occupied, matchStart, matchEnd)) {
					continue;
				}

				String value = text.substring(matchStart, matchEnd);
				// 필터가 거부한 매칭은 탐지로 인정하지 않는다 (토큰 발급 전에 거른다)
				if (!detectionFilter.accept(rule, value, matchStart)) {
					continue;
				}
				String token = tokenAssigner.assign(rule.getType(), value);
				detections.add(new Detection(rule.getType(), value, token, matchStart, matchEnd,
					normalizeRuleName(rule.getType(), rule.getName())));
				markAccepted(occupied, matchStart, matchEnd);
			}
		}

		addSqlIdentifierDetections(text, detections, occupied, tokenAssigner);
		propagateDetectedValues(text, detections, occupied);

		detections.sort(Comparator.comparingInt(Detection::startIndex));
		return new MaskingResult(text, replaceWithTokens(text, detections), List.copyOf(detections));
	}

	/** 규칙에 named group "sensitive"가 있으면 문맥은 남기고 해당 값 구간만 치환합니다. */
	private int sensitiveGroupStart(Matcher matcher) {
		try {
			int start = matcher.start("sensitive");
			return start >= 0 ? start : matcher.start();
		} catch (IllegalArgumentException e) {
			return matcher.start();
		}
	}

	private int sensitiveGroupEnd(Matcher matcher) {
		try {
			int end = matcher.end("sensitive");
			return end >= 0 ? end : matcher.end();
		} catch (IllegalArgumentException e) {
			return matcher.end();
		}
	}

	private void addSqlIdentifierDetections(String text, List<Detection> detections, boolean[] occupied,
		TokenAssigner tokenAssigner) {
		List<SqlIdentifierExtractor.Item> sqlItems = SqlIdentifierExtractor.extract(text);
		SqlSemanticTokenGenerator sqlTokens = SqlSemanticTokenGenerator.from(sqlItems);
		Set<String> rememberedSqlMappings = new HashSet<>();
		sqlTokens.aliasTokenToOriginal()
			.forEach((token, original) -> rememberSqlToken(tokenAssigner, rememberedSqlMappings, original, token));
		for (SqlIdentifierExtractor.Item item : sqlItems) {
			if (overlapsAccepted(occupied, item.start(), item.end())) {
				continue;
			}
			String token = sqlTokens.tokenFor(item);
			rememberSqlToken(tokenAssigner, rememberedSqlMappings, item.value(), token);
			detections.add(new Detection(MaskingType.SQL_QUERY, item.value(), token,
				item.start(), item.end(), normalizeRuleName(MaskingType.SQL_QUERY, item.ruleName())));
			markAccepted(occupied, item.start(), item.end());
		}
	}

	private void rememberSqlToken(TokenAssigner tokenAssigner, Set<String> rememberedSqlMappings, String value,
		String token) {
		if (rememberedSqlMappings.add(token + "\u0000" + value)) {
			tokenAssigner.remember(MaskingType.SQL_QUERY, value, token);
		}
	}

	private String normalizeRuleName(MaskingType type, String ruleName) {
		if (type != MaskingType.SQL_QUERY) {
			return ruleName;
		}
		return switch (ruleName) {
			case "SQL FROM 대상", "SQL 테이블명", "SQL 테이블명(FROM 목록)" -> "SQL 테이블명(FROM)";
			case "SQL JOIN 대상" -> "SQL 테이블명(JOIN)";
			case "SQL UPDATE 대상" -> "SQL 테이블명(UPDATE)";
			case "SQL 한정 식별자" -> "SQL 컬럼/스키마 식별자";
			default -> ruleName;
		};
	}

	/**
	 * 전파 마스킹: 규칙이 한 번 잡은 값이 텍스트의 다른 위치에 다시 등장하면
	 * (규칙이 그 위치를 못 잡았더라도) 같은 토큰으로 함께 마스킹합니다.
	 *
	 * <p>예: 엑셀 이름 컬럼에서 "이서연"이 잡히면, 같은 텍스트의
	 * "이서연의 주민번호는..." 문장 속 "이서연"도 마스킹됩니다.
	 * 이미 민감정보로 확정된 값의 리터럴 재등장만 치환하므로 새로운 오탐을 만들지 않고,
	 * 한 곳이라도 잡히면 전체가 가려져 부분 노출을 막습니다.
	 */
	private void propagateDetectedValues(String text, List<Detection> detections, boolean[] occupied) {
		List<Detection> seeds = List.copyOf(detections);
		Set<String> propagated = new HashSet<>();

		for (Detection seed : seeds) {
			// SQL 식별자는 CUSTOMER가 CUSTOMER_NAME 안에서 다시 잡히는 식의 부분 오탐 위험이 크다.
			// 반복되는 SQL 객체는 스캐너/규칙이 위치별로 직접 탐지하고, 토큰 발급기가 같은 값을 같은 토큰으로 묶는다.
			if (seed.type() == MaskingType.SQL_QUERY) {
				continue;
			}
			// 두 글자 미만 값은 우연히 일치할 확률이 높아 전파하지 않음
			if (seed.originalValue().length() < 2
				|| !propagated.add(seed.type().name() + ":" + seed.originalValue())) {
				continue;
			}

			Matcher matcher = Pattern.compile(Pattern.quote(seed.originalValue())).matcher(text);
			while (matcher.find()) {
				if (overlapsAccepted(occupied, matcher.start(), matcher.end())) {
					continue;
				}
				// 같은 (type, value)이므로 시드와 같은 토큰을 재사용 (토큰 발급기 호출 불필요)
				detections.add(new Detection(seed.type(), seed.originalValue(), seed.token(),
					matcher.start(), matcher.end(), seed.ruleName()));
				markAccepted(occupied, matcher.start(), matcher.end());
			}
		}
	}

	/**
	 * 사용자가 미리보기에서 직접 지정한 값을 추가로 마스킹합니다.
	 * (엔진이 못 잡은 거래처명, 프로젝트 코드명 등을 드래그해서 가리는 기능)
	 *
	 * <p>값을 정규식이 아닌 리터럴로 취급하므로 특수문자가 있어도 안전하며,
	 * 텍스트 안의 모든 등장 위치가 같은 토큰으로 치환됩니다.
	 *
	 * @param text 추가 마스킹을 적용할 텍스트 (보통 1차 마스킹이 끝난 미리보기 텍스트)
	 */
	public MaskingResult maskManually(String text, String value, MaskingType type, TokenAssigner tokenAssigner) {
		List<Detection> detections = new ArrayList<>();
		boolean[] occupied = new boolean[text.length()];

		Matcher matcher = Pattern.compile(Pattern.quote(value)).matcher(text);
		while (matcher.find()) {
			if (overlapsAccepted(occupied, matcher.start(), matcher.end())) {
				continue;
			}
			String token = tokenAssigner.assign(type, value);
			detections.add(new Detection(type, value, token, matcher.start(), matcher.end(), "수동 마스킹"));
			markAccepted(occupied, matcher.start(), matcher.end());
		}

		return new MaskingResult(text, replaceWithTokens(text, detections), List.copyOf(detections));
	}

	/**
	 * 텍스트(주로 GPT 응답) 속 마스킹 토큰을 원본값으로 되돌립니다.
	 *
	 * <p>매핑이 없는 토큰(TTL 만료, GPT가 지어낸 토큰 등)은 치환하지 않고 그대로 둡니다.
	 * 원본을 알 수 없는 상황에서 임의 값으로 바꾸는 것보다 토큰이 노출되는 편이
	 * 사용자가 상황을 인지하기에 안전하기 때문입니다.
	 */
	public String restore(String text, TokenResolver tokenResolver) {
		Map<String, String> resolvedTokens = new HashMap<>();
		Map<String, String> inferredAliasTokens = inferSqlAliasTokens(text, tokenResolver, resolvedTokens);
		Matcher matcher = TOKEN_PATTERN.matcher(text);
		StringBuilder restored = new StringBuilder();

		while (matcher.find()) {
			String original = resolveToken(matcher.group(), tokenResolver, resolvedTokens);
			String replacement = original != null ? original
				: inferredAliasTokens.getOrDefault(matcher.group(), matcher.group());
			matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(restored);

		return restored.toString();
	}

	private Map<String, String> inferSqlAliasTokens(String text, TokenResolver tokenResolver,
		Map<String, String> resolvedTokens) {
		Map<String, String> inferred = new HashMap<>();
		Matcher matcher = SQL_QUALIFIED_SEMANTIC_TOKEN.matcher(text);
		while (matcher.find()) {
			String token = matcher.group();
			String original = resolveToken(token, tokenResolver, resolvedTokens);
			if (original == null) {
				continue;
			}
			int dot = original.indexOf('.');
			if (dot <= 0) {
				continue;
			}
			inferred.putIfAbsent(matcher.group(1), original.substring(0, dot));
		}
		return inferred;
	}

	private String resolveToken(String token, TokenResolver tokenResolver, Map<String, String> resolvedTokens) {
		if (resolvedTokens.containsKey(token)) {
			return resolvedTokens.get(token);
		}
		String original = tokenResolver.resolve(token);
		resolvedTokens.put(token, original);
		return original;
	}

	/**
	 * 규칙의 정규식을 컴파일합니다. 문법 오류가 있으면 경고 로그를 남기고 null을 반환해
	 * 해당 규칙만 건너뜁니다. 규칙 하나가 깨져도 전체 마스킹이 중단되면 안 되기 때문입니다.
	 */
	private Pattern compileOrSkip(MaskingRule rule) {
		return patternCache.computeIfAbsent(rule.getPattern(), patternText -> {
			try {
				return Optional.of(Pattern.compile(patternText));
			} catch (PatternSyntaxException e) {
				log.warn("마스킹 규칙의 정규식 컴파일에 실패해 탐지에서 제외합니다. rule={}, pattern={}",
					rule.getName(), patternText, e);
				return Optional.empty();
			}
		}).orElse(null);
	}

	/** 새 매칭 구간 [start, end)가 이미 선점된 탐지 구간과 겹치는지 확인합니다. */
	private boolean overlapsAccepted(boolean[] occupied, int start, int end) {
		int safeStart = Math.max(0, start);
		int safeEnd = Math.min(occupied.length, end);
		for (int i = safeStart; i < safeEnd; i++) {
			if (occupied[i]) {
				return true;
			}
		}
		return false;
	}

	private void markAccepted(boolean[] occupied, int start, int end) {
		int safeStart = Math.max(0, start);
		int safeEnd = Math.min(occupied.length, end);
		for (int i = safeStart; i < safeEnd; i++) {
			occupied[i] = true;
		}
	}

	/**
	 * 탐지 구간을 토큰으로 치환합니다.
	 * 앞에서부터 바꾸면 이후 구간의 인덱스가 밀리므로 뒤에서부터 치환합니다.
	 * (Detection의 인덱스는 항상 "원문 기준"으로 유지되어 미리보기 하이라이트에 그대로 쓸 수 있습니다)
	 */
	private String replaceWithTokens(String text, List<Detection> detections) {
		StringBuilder masked = new StringBuilder(text);
		for (int i = detections.size() - 1; i >= 0; i--) {
			Detection detection = detections.get(i);
			masked.replace(detection.startIndex(), detection.endIndex(), detection.token());
		}
		return masked.toString();
	}
}
