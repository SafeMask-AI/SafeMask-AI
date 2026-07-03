package haitai.safemask.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yaml의 jwt 설정값을 바인딩하는 클래스입니다.
 *
 * 실제 값은 .env 파일의 JWT_SECRET, JWT_ACCESS_TOKEN_EXPIRATION,
 * JWT_REFRESH_TOKEN_EXPIRATION 환경변수에서 주입됩니다.
 * 시크릿과 만료시간을 코드에 하드코딩하지 않고 외부 설정으로 분리하여,
 * 환경(로컬/운영)마다 다른 값을 쓸 수 있고 시크릿이 저장소에 노출되지 않습니다.
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

	/** HS256 서명에 사용할 시크릿 문자열 (최소 32바이트 이상) */
	private String secret;

	/** Access Token 만료 시간 (밀리초) */
	private long accessTokenExpiration;

	/** Refresh Token 만료 시간 (밀리초) */
	private long refreshTokenExpiration;

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public long getAccessTokenExpiration() {
		return accessTokenExpiration;
	}

	public void setAccessTokenExpiration(long accessTokenExpiration) {
		this.accessTokenExpiration = accessTokenExpiration;
	}

	public long getRefreshTokenExpiration() {
		return refreshTokenExpiration;
	}

	public void setRefreshTokenExpiration(long refreshTokenExpiration) {
		this.refreshTokenExpiration = refreshTokenExpiration;
	}
}
