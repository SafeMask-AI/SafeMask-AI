package haitai.safemask.global.config;

import haitai.safemask.domain.auth.config.AuthCookieProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** prod 프로필의 위험한 기본값을 애플리케이션 기동 단계에서 차단합니다. */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProductionSecurityValidator implements ApplicationRunner {

	private final AuthCookieProperties cookieProperties;
	private final String storageBasePath;
	private final String serverAddress;

	public ProductionSecurityValidator(AuthCookieProperties cookieProperties,
		@Value("${safemask.storage.base-path:}") String storageBasePath,
		@Value("${server.address:}") String serverAddress) {
		this.cookieProperties = cookieProperties;
		this.storageBasePath = storageBasePath == null ? "" : storageBasePath.trim();
		this.serverAddress = serverAddress == null ? "" : serverAddress.trim();
	}

	@Override
	public void run(ApplicationArguments args) {
		validate();
	}

	void validate() {
		if (!cookieProperties.isSecure()) {
			throw new IllegalStateException("prod 프로필에서는 Refresh Token 쿠키 Secure 설정이 필수입니다.");
		}
		if (storageBasePath.isBlank() || !Path.of(storageBasePath).isAbsolute()) {
			throw new IllegalStateException("prod 프로필에서는 절대 경로 FILE_STORAGE_PATH 설정이 필수입니다.");
		}
		Path configured = Path.of(storageBasePath).toAbsolutePath().normalize();
		Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
		if (configured.startsWith(temporary)) {
			throw new IllegalStateException("prod 파일 저장소는 OS 임시 디렉터리 밖에 있어야 합니다.");
		}
		try {
			Files.createDirectories(configured);
			if (!Files.isWritable(configured)) {
				throw new IllegalStateException("prod 파일 저장소에 쓰기 권한이 없습니다.");
			}
		} catch (IOException exception) {
			throw new IllegalStateException("prod 파일 저장소를 준비할 수 없습니다.", exception);
		}
		if (!ListLoopbackAddress.isLoopback(serverAddress)) {
			throw new IllegalStateException("prod 프로필의 Spring Boot는 loopback 주소에만 바인딩해야 합니다.");
		}
	}

	/** 문자열 주소만 검증해 기동 시 DNS 조회나 외부 네트워크 접근이 발생하지 않게 합니다. */
	private static final class ListLoopbackAddress {
		private static boolean isLoopback(String address) {
			return "127.0.0.1".equals(address) || "::1".equals(address) || "localhost".equalsIgnoreCase(address);
		}
	}
}
