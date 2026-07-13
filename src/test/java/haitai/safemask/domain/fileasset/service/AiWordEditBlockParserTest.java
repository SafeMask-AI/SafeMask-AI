package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiWordEditBlockParserTest {

	private final AiWordEditBlockParser parser = new AiWordEditBlockParser(new ObjectMapper());

	@Test
	void parsesWordEditBlock() {
		String answer = """
			[[SAFEMASK_WORD_EDIT file="보고서.docx"]]
			{"result":"보고서_수정.docx","ops":[{"op":"replace_text","from":"[PERSON_001]","to":"[PERSON_002]"}]}
			[[/SAFEMASK_WORD_EDIT]]
			""";

		var blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).targetFileName()).isEqualTo("보고서.docx");
		assertThat(blocks.get(0).instruction().ops().get(0).op()).isEqualTo("replace_text");
	}
}
