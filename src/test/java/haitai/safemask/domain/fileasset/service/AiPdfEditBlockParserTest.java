package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AiPdfEditBlockParserTest {

	private final AiPdfEditBlockParser parser = new AiPdfEditBlockParser(new ObjectMapper());

	@Test
	void parsesDesignAwarePdfSections() {
		String answer = """
			[[SAFEMASK_PDF_EDIT file="보고서.pdf"]]
			{"result":"보고서_요약.pdf","ops":[
			  {"op":"append_page","title":"요약","subtitle":"핵심 내용","sections":[
			    {"heading":"결론","text":"요약 본문","items":["후속 검토"]}
			  ]},
			  {"op":"add_page_numbers"}
			]}
			[[/SAFEMASK_PDF_EDIT]]
			""";

		var blocks = parser.parse(answer);

		assertThat(blocks).hasSize(1);
		assertThat(blocks.get(0).targetFileName()).isEqualTo("보고서.pdf");
		var append = blocks.get(0).instruction().ops().get(0);
		assertThat(append.subtitle()).isEqualTo("핵심 내용");
		assertThat(append.sections()).singleElement()
			.satisfies(section -> {
				assertThat(section.heading()).isEqualTo("결론");
				assertThat(section.items()).containsExactly("후속 검토");
			});
	}
}
