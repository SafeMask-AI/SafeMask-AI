package haitai.safemask.domain.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.auth.config.AuthRequestProtectionProperties;
import haitai.safemask.domain.auth.security.AuthRequestProtectionService.RequestType;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("unchecked")
class AuthRequestProtectionServiceTest {

	@Mock
	private StringRedisTemplate redisTemplate;
	private AuthRequestProtectionProperties properties;
	private AuthRequestProtectionService service;
	private MockHttpServletRequest request;

	@BeforeEach
	void setUp() {
		properties = new AuthRequestProtectionProperties();
		properties.setLoginLimit(2);
		service = new AuthRequestProtectionService(redisTemplate, properties);
		request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.15");
	}

	@Test
	void allowedAttemptUsesHashedClientKey() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

		service.check(RequestType.LOGIN, request);

		ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
		verify(redisTemplate).execute(any(RedisScript.class), keys.capture(), any());
		assertThat(keys.getValue()).singleElement()
			.asString().startsWith("safemask:auth-rate:login:")
			.doesNotContain("203.0.113.15");
	}

	@Test
	void loginFailureAuditDoesNotWriteRawIdentifierOrAddress(CapturedOutput output) {
		service.auditLoginFailure(request, "2026001", ErrorCode.LOGIN_FAILED);

		assertThat(output).doesNotContain("203.0.113.15", "2026001")
			.contains("clientHash=", "loginIdHash=", "AUTH_401_1");
	}

	@Test
	void attemptBeyondLimitReturns429ErrorCode() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(3L);

		assertThatThrownBy(() -> service.check(RequestType.LOGIN, request))
			.isInstanceOfSatisfying(CustomException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.AUTH_RATE_LIMITED));
	}

	@Test
	void redisFailureDoesNotFailOpen() {
		when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
			.thenThrow(new RedisConnectionFailureException("down"));

		assertThatThrownBy(() -> service.check(RequestType.LOGIN, request))
			.isInstanceOfSatisfying(CustomException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.AUTH_PROTECTION_UNAVAILABLE));
	}

	@Test
	void disabledProtectionDoesNotTouchRedis() {
		properties.setEnabled(false);

		service.check(RequestType.LOGIN, request);

		verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
	}
}
