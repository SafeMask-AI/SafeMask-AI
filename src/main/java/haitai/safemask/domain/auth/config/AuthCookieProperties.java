package haitai.safemask.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Refresh Token 쿠키의 환경별 전송 보안 정책입니다. */
@Component
@ConfigurationProperties(prefix = "safemask.auth.cookie")
public class AuthCookieProperties {

	/** HTTPS에서만 쿠키를 전송할지 여부. 운영 prod 프로필에서는 반드시 true여야 합니다. */
	private boolean secure;

	public boolean isSecure() {
		return secure;
	}

	public void setSecure(boolean secure) {
		this.secure = secure;
	}
}
