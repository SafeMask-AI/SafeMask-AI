package haitai.safemask.domain.maskingrule.service;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleUpsertRequest;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Component;

/** 사용자 정규식이 마스킹 요청 스레드를 장시간 점유하지 않도록 보수적으로 제한합니다. */
@Component
public class MaskingRuleDefinitionValidator {

	private static final Pattern BACK_REFERENCE = Pattern.compile("\\\\[1-9]");
	private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*}][^)]*\\)[+*{]");
	private static final Pattern REPEATED_ALTERNATION = Pattern.compile("\\([^)]*\\|[^)]*\\)[+*{]");

	public ValidatedRule validate(MaskingRuleUpsertRequest request) {
		String name = request.name().trim();
		String pattern = request.pattern().trim();
		String description = request.description() == null || request.description().isBlank()
			? null : request.description().trim();
		String reason = request.reason().trim();

		if (request.type() == MaskingType.SQL_QUERY) {
			throw invalid("사용자 정의 규칙은 SQL 전용 파서를 변경할 수 없습니다.");
		}
		if (request.ruleKind() == MaskingRuleKind.KEYWORD) {
			if (pattern.length() < 2) {
				throw invalid("키워드는 두 글자 이상이어야 합니다.");
			}
			return new ValidatedRule(name, request.type(), request.ruleKind(), pattern,
				request.priority(), description, reason);
		}

		validateRegex(pattern);
		return new ValidatedRule(name, request.type(), request.ruleKind(), pattern,
			request.priority(), description, reason);
	}

	/** 저장된 규칙을 활성화하거나 테스트하기 전에 다시 검증합니다. */
	public void validateStored(MaskingRuleKind kind, MaskingType type, String pattern) {
		if (type == MaskingType.SQL_QUERY) {
			throw invalid("사용자 정의 규칙은 SQL 전용 파서를 변경할 수 없습니다.");
		}
		if (kind == MaskingRuleKind.KEYWORD) {
			if (pattern == null || pattern.length() < 2) {
				throw invalid("키워드는 두 글자 이상이어야 합니다.");
			}
			return;
		}
		validateRegex(pattern);
	}

	private void validateRegex(String expression) {
		if (expression.contains(".*") || expression.contains(".+")) {
			throw invalid("범위가 제한되지 않은 와일드카드(.* 또는 .+)는 사용할 수 없습니다.");
		}
		if (expression.contains("(?=") || expression.contains("(?!")
			|| expression.contains("(?<=") || expression.contains("(?<!")) {
			throw invalid("사용자 정의 정규식에는 전후방 탐색을 사용할 수 없습니다.");
		}
		if (BACK_REFERENCE.matcher(expression).find()) {
			throw invalid("사용자 정의 정규식에는 역참조를 사용할 수 없습니다.");
		}
		if (NESTED_QUANTIFIER.matcher(expression).find()) {
			throw invalid("반복 그룹을 다시 반복하는 위험한 정규식은 사용할 수 없습니다.");
		}
		if (REPEATED_ALTERNATION.matcher(expression).find()) {
			throw invalid("선택 그룹을 반복하는 위험한 정규식은 사용할 수 없습니다.");
		}
		try {
			Pattern compiled = Pattern.compile(expression);
			if (compiled.matcher("").find()) {
				throw invalid("빈 문자열과 일치하는 정규식은 사용할 수 없습니다.");
			}
		} catch (PatternSyntaxException exception) {
			throw new CustomException(ErrorCode.MASKING_RULE_INVALID,
				"정규식 문법이 올바르지 않습니다: " + exception.getDescription());
		}
	}

	private CustomException invalid(String message) {
		return new CustomException(ErrorCode.MASKING_RULE_INVALID, message);
	}

	public record ValidatedRule(String name, MaskingType type, MaskingRuleKind ruleKind,
		String pattern, Integer priority, String description, String reason) {
	}
}
