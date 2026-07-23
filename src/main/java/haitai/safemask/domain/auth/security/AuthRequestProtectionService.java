package haitai.safemask.domain.auth.security;

import haitai.safemask.domain.auth.config.AuthRequestProtectionProperties;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * 인증 API 요청 제한과 로그인 실패 감사를 담당합니다.
 *
 * <p>NGINX가 전달한 주소는 Spring의 신뢰 프록시 처리 후 {@code getRemoteAddr()}에 반영됩니다.
 * Redis 키와 로그에는 IP·사번 원문을 남기지 않고 SHA-256 축약 지문만 사용합니다.
 */
@Slf4j
@Service
public class AuthRequestProtectionService {

	private static final String KEY_PREFIX = "safemask:auth-rate:";
	private static final RedisScript<Long> INCREMENT_SCRIPT = RedisScript.of("""
		local count = redis.call('INCR', KEYS[1])
		if count == 1 then
		  redis.call('PEXPIRE', KEYS[1], ARGV[1])
		end
		return count
		""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final AuthRequestProtectionProperties properties;

	public AuthRequestProtectionService(StringRedisTemplate redisTemplate,
		AuthRequestProtectionProperties properties) {
		this.redisTemplate = redisTemplate;
		this.properties = properties;
	}

	public void check(RequestType type, HttpServletRequest request) {
		if (!properties.isEnabled()) {
			return;
		}
		Policy policy = policy(type);
		String clientHash = fingerprint(clientAddress(request));
		String key = KEY_PREFIX + type.key() + ':' + clientHash;
		try {
			Long count = redisTemplate.execute(INCREMENT_SCRIPT, List.of(key),
				String.valueOf(Math.max(1L, policy.window().toMillis())));
			if (count == null) {
				throw new CustomException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE);
			}
			if (count > policy.limit()) {
				// 차단 이후 매 요청을 로그로 남기면 공격자가 디스크를 채울 수 있어 최초 초과만 기록합니다.
				if (count == policy.limit() + 1L) {
					log.warn("Authentication API rate limit exceeded: type={}, clientHash={}", type, clientHash);
				}
				throw new CustomException(ErrorCode.AUTH_RATE_LIMITED);
			}
		} catch (DataAccessException exception) {
			// 보호 저장소 장애를 무제한 인증 시도로 우회하지 못하도록 공개 인증 API는 fail-closed 처리합니다.
			throw new CustomException(ErrorCode.AUTH_PROTECTION_UNAVAILABLE, exception);
		}
	}

	public void auditLoginFailure(HttpServletRequest request, String loginId, ErrorCode outcome) {
		log.warn("Authentication failed: clientHash={}, loginIdHash={}, outcome={}",
			fingerprint(clientAddress(request)), fingerprint(loginId), outcome.getCode());
	}

	private Policy policy(RequestType type) {
		return switch (type) {
			case LOGIN -> new Policy(properties.getLoginLimit(), properties.getLoginWindow());
			case SIGNUP -> new Policy(properties.getSignupLimit(), properties.getSignupWindow());
			case DUPLICATE_CHECK -> new Policy(properties.getDuplicateCheckLimit(),
				properties.getDuplicateCheckWindow());
			case REFRESH -> new Policy(properties.getRefreshLimit(), properties.getRefreshWindow());
		};
	}

	private String clientAddress(HttpServletRequest request) {
		return request == null || request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
	}

	private String fingerprint(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 8);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available.", exception);
		}
	}

	private record Policy(int limit, Duration window) {
	}

	public enum RequestType {
		LOGIN("login"),
		SIGNUP("signup"),
		DUPLICATE_CHECK("duplicate"),
		REFRESH("refresh");

		private final String key;

		RequestType(String key) {
			this.key = key;
		}

		public String key() {
			return key;
		}
	}
}
