package haitai.safemask.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.auth.config.AuthCookieProperties;
import haitai.safemask.domain.auth.dto.LoginRequest;
import haitai.safemask.domain.auth.dto.LoginResponse;
import haitai.safemask.domain.auth.dto.LoginResult;
import haitai.safemask.domain.auth.security.AuthRequestProtectionService;
import haitai.safemask.domain.auth.security.AuthRequestProtectionService.RequestType;
import haitai.safemask.domain.auth.service.AuthService;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import haitai.safemask.global.jwt.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthControllerSecurityTest {

	private AuthService authService;
	private AuthRequestProtectionService requestProtectionService;
	private AuthController controller;
	private MockHttpServletRequest servletRequest;

	@BeforeEach
	void setUp() {
		authService = mock(AuthService.class);
		requestProtectionService = mock(AuthRequestProtectionService.class);
		JwtProperties jwtProperties = new JwtProperties();
		jwtProperties.setRefreshTokenExpiration(1_209_600_000L);
		AuthCookieProperties cookieProperties = new AuthCookieProperties();
		cookieProperties.setSecure(true);
		controller = new AuthController(authService, jwtProperties, cookieProperties, requestProtectionService);
		servletRequest = new MockHttpServletRequest();
		servletRequest.setRemoteAddr("203.0.113.15");
	}

	@Test
	void httpsLoginIssuesSecureHttpOnlyStrictCookieAfterRateLimitCheck() {
		LoginRequest request = new LoginRequest("2026001", "Password1", true);
		when(authService.login(any())).thenReturn(new LoginResult(
			new LoginResponse(1L, "사용자", "개발", "USER", "access-token"), "refresh-token"));

		ResponseEntity<LoginResponse> response = controller.login(servletRequest, request);

		String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(cookie)
			.contains("refreshToken=refresh-token", "Path=/api/auth", "Secure", "HttpOnly", "SameSite=Strict")
			.contains("Max-Age=1209600");
		verify(requestProtectionService).check(RequestType.LOGIN, servletRequest);
	}

	@Test
	void rejectedLoginIsAuditedWithoutChangingPublicError() {
		LoginRequest request = new LoginRequest("2026001", "wrong", false);
		CustomException failure = new CustomException(ErrorCode.LOGIN_FAILED);
		when(authService.login(any())).thenThrow(failure);

		assertThatThrownBy(() -> controller.login(servletRequest, request)).isSameAs(failure);

		verify(requestProtectionService).auditLoginFailure(servletRequest, "2026001", ErrorCode.LOGIN_FAILED);
	}

	@Test
	void logoutExpiresCookieWithSameSecurityAttributes() {
		ResponseEntity<Void> response = controller.logout("refresh-token");

		assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
			.contains("refreshToken=", "Max-Age=0", "Path=/api/auth", "Secure", "HttpOnly", "SameSite=Strict");
		verify(authService).logout("refresh-token");
	}
}
