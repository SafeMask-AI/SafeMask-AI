package haitai.safemask.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AdminEmptyStateStyleTest {

	@Test
	void hiddenEmptyStateCannotBeForcedVisibleByDisplayGrid() throws IOException {
		String css = new ClassPathResource("static/css/admin.css")
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(css.replaceAll("\\s+", "")).contains(".empty[hidden]{display:none;}");
	}
}
