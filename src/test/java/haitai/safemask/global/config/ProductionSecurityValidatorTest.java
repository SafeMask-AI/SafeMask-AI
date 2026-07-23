package haitai.safemask.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.domain.auth.config.AuthCookieProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProductionSecurityValidatorTest {

	@Test
	void prodRejectsInsecureRefreshCookie() {
		AuthCookieProperties cookie = cookie(false);
		String storage = Path.of(System.getProperty("user.dir"), "build", "prod-storage").toAbsolutePath().toString();

		assertThatThrownBy(() -> new ProductionSecurityValidator(cookie, storage, "127.0.0.1").validate())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Secure");
	}

	@Test
	void prodRejectsRelativeStoragePathAndPublicBinding() {
		assertThatThrownBy(() -> new ProductionSecurityValidator(cookie(true), "data/files", "127.0.0.1").validate())
			.hasMessageContaining("절대 경로");

		String storage = Path.of(System.getProperty("user.dir"), "build", "prod-storage").toAbsolutePath().toString();
		assertThatThrownBy(() -> new ProductionSecurityValidator(cookie(true), storage, "0.0.0.0").validate())
			.hasMessageContaining("loopback");
	}

	@Test
	void secureLoopbackConfigurationPreparesDedicatedStorage() {
		String storage = Path.of(System.getProperty("user.dir"), "build", "prod-storage").toAbsolutePath().toString();

		assertThatCode(() -> new ProductionSecurityValidator(cookie(true), storage, "127.0.0.1").validate())
			.doesNotThrowAnyException();
	}

	private AuthCookieProperties cookie(boolean secure) {
		AuthCookieProperties properties = new AuthCookieProperties();
		properties.setSecure(secure);
		return properties;
	}
}
