package haitai.safemask.domain.masking.engine;

import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.entity.MaskingRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
	private static final Pattern TOKEN_PATTERN = Pattern.compile("\\[[A-Z_]+_\\d{3}]");

	/**
	 * 규칙 정규식의 컴파일 결과 캐시. (패턴 문자열 → 컴파일 결과)
	 * 규칙 수가 적고 요청마다 재사용되므로 크기 제한 없이 캐시해도 안전합니다.
	 * 컴파일에 실패한 패턴은 empty로 캐시해 요청마다 재시도·로그 반복을 막습니다.
	 */
	private final Map<String, Optional<Pattern>> patternCache = new ConcurrentHashMap<>();

	/**
	 * 활성 규칙들을 우선순위 순으로 적용해 텍스트를 마스킹합니다.
	 *
	 * @param text          마스킹할 원문
	 * @param rules         적용할 규칙 (우선순위 오름차순으로 정렬된 상태여야 함)
	 * @param tokenAssigner 토큰 발급기 (운영: Redis, 테스트: 인메모리)
	 */
	public MaskingResult mask(String text, List<MaskingRule> rules, TokenAssigner tokenAssigner) {
		List<Detection> detections = new ArrayList<>();

		for (MaskingRule rule : rules) {
			Pattern pattern = compileOrSkip(rule);
			if (pattern == null) {
				continue;
			}

			Matcher matcher = pattern.matcher(text);
			while (matcher.find()) {
				// 빈 문자열 매칭 방어: 잘못 등록된 규칙(예: ".*?")이 위치마다 무의미한 토큰을 만드는 것 방지
				if (matcher.start() == matcher.end()) {
					continue;
				}
				if (overlapsAccepted(detections, matcher.start(), matcher.end())) {
					continue;
				}

				String value = matcher.group();
				String token = tokenAssigner.assign(rule.getType(), value);
				detections.add(new Detection(rule.getType(), value, token, matcher.start(), matcher.end()));
			}
		}

		detections.sort(Comparator.comparingInt(Detection::startIndex));
		return new MaskingResult(text, replaceWithTokens(text, detections), List.copyOf(detections));
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

		Matcher matcher = Pattern.compile(Pattern.quote(value)).matcher(text);
		while (matcher.find()) {
			String token = tokenAssigner.assign(type, value);
			detections.add(new Detection(type, value, token, matcher.start(), matcher.end()));
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
		Matcher matcher = TOKEN_PATTERN.matcher(text);
		StringBuilder restored = new StringBuilder();

		while (matcher.find()) {
			String original = tokenResolver.resolve(matcher.group());
			String replacement = original != null ? original : matcher.group();
			matcher.appendReplacement(restored, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(restored);

		return restored.toString();
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
	private boolean overlapsAccepted(List<Detection> accepted, int start, int end) {
		for (Detection detection : accepted) {
			if (start < detection.endIndex() && detection.startIndex() < end) {
				return true;
			}
		}
		return false;
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
