package haitai.safemask.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionDeploymentConfigurationTest {

	@Test
	void prodProfileAndNginxExampleKeepHttpsFileAndSsePolicies() throws Exception {
		String profile = Files.readString(Path.of("src/main/resources/application-prod.yaml"), StandardCharsets.UTF_8);
		String nginx = Files.readString(Path.of("deploy/nginx/safemask.conf.example"), StandardCharsets.UTF_8);

		assertThat(profile)
			.contains("on-profile: prod", "127.0.0.1", "forward-headers-strategy: native",
				"secure: ${AUTH_COOKIE_SECURE:true}", "base-path: ${FILE_STORAGE_PATH:}");
		assertThat(nginx)
			.contains("return 301 https://$host$request_uri", "client_max_body_size 25m",
				"proxy_buffering off", "proxy_read_timeout 240s", "X-Forwarded-Proto $scheme",
				"Strict-Transport-Security");
	}
}
