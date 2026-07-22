package haitai.safemask.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AdminMonitoringUiTest {

	@Test
	void allAdminPagesExposeTheMonitoringNavigation() throws IOException {
		assertThat(resource("templates/admin/members.html")).contains("href=\"/admin/monitoring\"");
		assertThat(resource("templates/admin/rules.html")).contains("href=\"/admin/monitoring\"");
		assertThat(resource("templates/admin/monitoring.html"))
			.contains("운영 모니터링", "id=\"monitoringStatusBanner\"", "id=\"trendChart\"", "class=\"metric-icon\"")
			.doesNotContain("<tbody", "최근 요청", "metric-sparkline");
	}

	@Test
	void dashboardRendersChartsWithoutExternalScriptOrUnsafeHtmlFromApi() throws IOException {
		String script = resource("static/js/admin-monitoring.js");

		assertThat(script)
			.contains("/api/admin/monitoring")
			.contains("createElementNS", "textContent")
			.doesNotContain("innerHTML", "https://");
	}

	private String resource(String path) throws IOException {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}
}
