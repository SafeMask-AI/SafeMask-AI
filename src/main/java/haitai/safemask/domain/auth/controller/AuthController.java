package haitai.safemask.domain.auth.controller;

import haitai.safemask.domain.auth.dto.DuplicateCheckResponse;
import haitai.safemask.domain.auth.dto.LoginRequest;
import haitai.safemask.domain.auth.dto.LoginResponse;
import haitai.safemask.domain.auth.dto.LoginResult;
import haitai.safemask.domain.auth.dto.SignupRequest;
import haitai.safemask.domain.auth.dto.SignupResponse;
import haitai.safemask.domain.auth.dto.TokenRefreshResponse;
import haitai.safemask.domain.auth.dto.TokenRefreshResult;
import haitai.safemask.domain.auth.service.AuthService;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import haitai.safemask.global.jwt.JwtProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 관련 REST API입니다. (/api/auth/** 는 SecurityConfig에서 permitAll)
 *
 * 회원가입은 프론트에서 단계별로 진행합니다:
 * 1단계 사번 입력 → GET /api/auth/check-login-id 로 중복 확인
 * 2단계 이메일 입력 → GET /api/auth/check-email 로 중복 확인
 * 3단계 이름/부서/비밀번호 입력 → POST /api/auth/signup 으로 최종 가입
 *
 * 토큰 전달 방식:
 * - Access Token: 응답 본문(JSON) → 클라이언트가 Authorization 헤더에 사용
 * - Refresh Token: HttpOnly 쿠키 → JS가 읽을 수 없어 XSS로 탈취 불가.
 *   브라우저가 /api/auth 경로 요청에만 자동으로 실어 보냅니다.
 */
@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {

	/** Refresh Token을 담는 쿠키 이름 */
	private static final String REFRESH_TOKEN_COOKIE = "refreshToken";

	private final AuthService authService;
	private final JwtProperties jwtProperties;

	public AuthController(AuthService authService, JwtProperties jwtProperties) {
		this.authService = authService;
		this.jwtProperties = jwtProperties;
	}

	// ==================== 회원가입 단계별 확인 ====================

	/**
	 * 회원가입 1단계: 사번 중복 확인
	 * 응답의 exists가 true면 이미 가입된 사번입니다.
	 */
	@GetMapping("/check-login-id")
	public ResponseEntity<DuplicateCheckResponse> checkLoginId(
		@RequestParam("loginId")
		@NotBlank(message = "사번은 필수입니다.")
		String loginId
	) {
		return ResponseEntity.ok(authService.checkLoginId(loginId));
	}

	/**
	 * 회원가입 2단계: 이메일 중복 확인
	 * 응답의 exists가 true면 이미 가입된 이메일입니다.
	 */
	@GetMapping("/check-email")
	public ResponseEntity<DuplicateCheckResponse> checkEmail(
		@RequestParam("email")
		@NotBlank(message = "이메일은 필수입니다.")
		@Email(message = "올바른 이메일 형식이 아닙니다.")
		String email
	) {
		return ResponseEntity.ok(authService.checkEmail(email));
	}

	// ==================== 회원가입 ====================

	/**
	 * 회원가입 최종 단계: 사번·이메일·이름·부서·비밀번호로 회원을 생성합니다.
	 * 성공 시 201 Created와 함께 생성된 회원 정보를 반환합니다.
	 */
	@PostMapping("/signup")
	public ResponseEntity<SignupResponse> signup(@RequestBody @Valid SignupRequest request) {
		SignupResponse response = authService.signup(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	// ==================== 로그인 ====================

	/**
	 * 사번과 비밀번호로 로그인합니다.
	 * Access Token은 응답 본문으로, Refresh Token은 HttpOnly 쿠키로 발급됩니다.
	 * 이후 API 호출 시 "Authorization: Bearer {accessToken}" 헤더를 사용합니다.
	 */
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
		LoginResult result = authService.login(request);

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE,
				createRefreshTokenCookie(result.refreshToken(), Boolean.TRUE.equals(request.rememberMe())).toString())
			.body(result.response());
	}

	// ==================== 토큰 갱신 ====================

	/**
	 * Access Token 만료 시 새 토큰을 발급받습니다.
	 * Refresh Token은 브라우저가 쿠키로 자동 전송하므로 요청 본문이 필요 없습니다.
	 * Rotation 방식이라 응답 시 쿠키의 Refresh Token도 새 값으로 교체됩니다.
	 */
	@PostMapping("/refresh")
	public ResponseEntity<TokenRefreshResponse> refresh(
		@CookieValue(value = REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
		@RequestHeader(value = "X-Remember-Login", required = false) String rememberLogin
	) {
		// 쿠키 자체가 없으면(만료·삭제됨) 재로그인 대상입니다.
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
		}

		TokenRefreshResult result = authService.refresh(refreshToken);

		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE,
				createRefreshTokenCookie(result.refreshToken(), Boolean.parseBoolean(rememberLogin)).toString())
			.body(result.response());
	}

	// ==================== 로그아웃 ====================

	/**
	 * 로그아웃: 서버의 Refresh Token을 삭제하고 쿠키도 즉시 만료시킵니다.
	 * 쿠키가 이미 없어도(이중 로그아웃 등) 실패시키지 않습니다.
	 */
	@PostMapping("/logout")
	public ResponseEntity<Void> logout(
		@CookieValue(value = REFRESH_TOKEN_COOKIE, required = false) String refreshToken
	) {
		if (refreshToken != null && !refreshToken.isBlank()) {
			authService.logout(refreshToken);
		}

		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, expireRefreshTokenCookie().toString())
			.build();
	}

	// ==================== private ====================

	/**
	 * Refresh Token을 담을 HttpOnly 쿠키를 생성합니다.
	 *
	 * - httpOnly: JS(document.cookie)로 접근 불가 → XSS로 탈취 방지
	 * - sameSite=Strict: 다른 사이트에서 시작된 요청에는 쿠키 미전송 → CSRF 방지
	 * - path=/api/auth: 인증 API 요청에만 쿠키가 실려 불필요한 노출 최소화
	 * - secure(false): 로컬 http 개발 환경용. HTTPS로 배포할 때 true로 바꿔야 합니다.
	 */
	private ResponseCookie createRefreshTokenCookie(String token, boolean persistent) {
		ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(REFRESH_TOKEN_COOKIE, token)
			.httpOnly(true)
			.secure(false)
			.sameSite("Strict")
			.path("/api/auth");
		if (persistent) {
			builder.maxAge(Duration.ofMillis(jwtProperties.getRefreshTokenExpiration()));
		}
		return builder.build();
	}

	/** maxAge=0 쿠키를 내려보내 브라우저에 저장된 Refresh Token 쿠키를 삭제합니다. */
	private ResponseCookie expireRefreshTokenCookie() {
		return ResponseCookie.from(REFRESH_TOKEN_COOKIE, "")
			.httpOnly(true)
			.secure(false)
			.sameSite("Strict")
			.path("/api/auth")
			.maxAge(0)
			.build();
	}
}
