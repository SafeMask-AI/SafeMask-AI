package haitai.safemask.domain.maskingrule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingrule.dto.MaskingRuleUpsertRequest;
import haitai.safemask.domain.maskingrule.enums.MaskingRuleKind;
import haitai.safemask.global.exception.CustomException;
import org.junit.jupiter.api.Test;

class MaskingRuleDefinitionValidatorTest {

	private final MaskingRuleDefinitionValidator validator = new MaskingRuleDefinitionValidator();

	@Test
	void keywordIsTrimmedAndAccepted() {
		var result = validator.validate(request(MaskingRuleKind.KEYWORD, "  극비 프로젝트.A  "));

		assertThat(result.pattern()).isEqualTo("극비 프로젝트.A");
	}

	@Test
	void dangerousOrOverbroadRegexIsRejected() {
		assertThatThrownBy(() -> validator.validate(request(MaskingRuleKind.REGEX, ".*비밀")))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("와일드카드");
		assertThatThrownBy(() -> validator.validate(request(MaskingRuleKind.REGEX, "(가+)+")))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("위험한 정규식");
		assertThatThrownBy(() -> validator.validate(request(MaskingRuleKind.REGEX, "(비밀)\\1")))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("역참조");
		assertThatThrownBy(() -> validator.validate(request(MaskingRuleKind.REGEX, "(a|aa)+")))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("선택 그룹");
	}

	@Test
	void sqlParserRuleCannotBeAddedByAdministrator() {
		var request = new MaskingRuleUpsertRequest("SQL", MaskingType.SQL_QUERY,
			MaskingRuleKind.KEYWORD, "SELECT", 5000, null, "테스트", null);

		assertThatThrownBy(() -> validator.validate(request))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("SQL 전용 파서");
	}

	private MaskingRuleUpsertRequest request(MaskingRuleKind kind, String pattern) {
		return new MaskingRuleUpsertRequest("사내 규칙", MaskingType.CUSTOM, kind,
			pattern, 5000, "테스트 규칙", "보호 범위 추가", null);
	}
}
