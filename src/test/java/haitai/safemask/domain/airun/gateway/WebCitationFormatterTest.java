package haitai.safemask.domain.airun.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WebCitationFormatterTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WebCitationFormatter formatter = new WebCitationFormatter();

	@Test
	void createsClickableInlineCitationAndSourceList() throws Exception {
		var annotations = objectMapper.readTree("""
			[{"type":"url_citation","start_index":0,"end_index":6,
			  "url":"https://example.com/report?a=1","title":"공식 보고서"}]
			""");

		var result = formatter.format("최신 자료입니다.", annotations);

		assertThat(result.text())
			.contains("([출처](https://example.com/report?a=1))")
			.contains("### 출처")
			.contains("[공식 보고서](https://example.com/report?a=1) · example.com");
		assertThat(result.sources()).containsExactly(
			new WebSource("공식 보고서", "https://example.com/report?a=1", "example.com"));
	}

	@Test
	void rejectsExecutableAndPrivateSourceUrls() throws Exception {
		var annotations = objectMapper.readTree("""
			[
			  {"type":"url_citation","end_index":2,"url":"javascript:alert(1)","title":"위험"},
			  {"type":"url_citation","end_index":2,"url":"http://127.0.0.1/admin","title":"내부"}
			]
			""");

		var result = formatter.format("답변", annotations);

		assertThat(result.text()).isEqualTo("답변");
		assertThat(result.sources()).isEmpty();
	}
}
