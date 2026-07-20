package haitai.safemask.global.security;

import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.jwt.JwtAuthenticationFilter;
import haitai.safemask.global.jwt.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 전역 설정입니다.
 *
 * SafeMask는 세션 대신 JWT로 인증 상태를 관리합니다.
 * - 로그인/회원가입 등 /api/auth/** 경로는 토큰 없이 접근을 허용하고,
 * - 그 외 요청은 JwtAuthenticationFilter가 검증한 토큰이 있어야 접근할 수 있습니다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtTokenProvider jwtTokenProvider;
	private final MemberRepository memberRepository;

	public SecurityConfig(JwtTokenProvider jwtTokenProvider, MemberRepository memberRepository) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.memberRepository = memberRepository;
	}

	/**
	 * 비밀번호 해싱에 사용할 인코더입니다.
	 *
	 * BCrypt는 단방향 해시 + 솔트가 내장되어 있어 같은 비밀번호라도
	 * 매번 다른 해시가 저장되며, DB가 유출되어도 원문 복원이 어렵습니다.
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			// JWT 방식에서는 브라우저 세션 쿠키를 쓰지 않으므로 CSRF 보호가 필요 없습니다.
			.csrf(csrf -> csrf.disable())
			// 폼 로그인/HTTP Basic 대신 자체 로그인 API(/api/auth/login)를 사용합니다.
			.formLogin(form -> form.disable())
			.httpBasic(basic -> basic.disable())
			// 서버가 세션을 만들지 않도록 하여 인증 상태를 온전히 토큰에만 의존하게 합니다.
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

			.authorizeHttpRequests(auth -> auth
				// SSE/DeferredResult의 ASYNC 디스패치는 최초 REQUEST 디스패치에서 이미 JWT 인증과
				// URL 권한 검사를 통과한 동일 요청의 후속 처리입니다. Stateless 정책 때문에 이때
				// SecurityContext가 비어 있어도 다시 403으로 막지 않습니다. DispatcherType은 서블릿
				// 컨테이너가 정하므로 외부 요청이 이 규칙으로 일반 API 인증을 우회할 수 없습니다.
				.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
				// 회원가입·로그인·토큰 갱신은 토큰이 없는 상태에서 호출되므로 허용합니다.
				.requestMatchers("/api/auth/**").permitAll()
				// Thymeleaf 화면(로그인/회원가입)과 정적 리소스 (필요 시 경로 추가)
				.requestMatchers("/", "/login", "/signup", "/account/pending", "/account/rejected",
					"/chat", "/admin", "/css/**", "/js/**", "/images/**",
					"/favicon.ico", "/error").permitAll()
				// 관리자 전용 API
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				// 그 외 모든 요청은 유효한 Access Token 필요
				.anyRequest().authenticated()
			)

			// 인증 실패(토큰 없음/만료/무효) 시 401 JSON을 반환합니다.
			// 응답 형식은 GlobalExceptionHandler의 ErrorResponse와 동일하게 맞춥니다.
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, authException) ->
					writeErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
						"COMMON_401", "로그인이 필요합니다.", request.getRequestURI()))
				// 인증은 됐지만 권한(ROLE)이 부족한 경우 403 JSON을 반환합니다.
				.accessDeniedHandler((request, response, accessDeniedException) ->
					writeErrorResponse(response, HttpServletResponse.SC_FORBIDDEN,
						"COMMON_403", "접근 권한이 없습니다.", request.getRequestURI()))
			)

			// UsernamePasswordAuthenticationFilter보다 앞에 JWT 필터를 두어
			// 컨트롤러 도달 전에 토큰 기반 인증이 완료되도록 합니다.
			.addFilterBefore(
				new JwtAuthenticationFilter(jwtTokenProvider, memberRepository),
				UsernamePasswordAuthenticationFilter.class
			);

		return http.build();
	}

	/**
	 * Security 필터 단계에서 발생한 인증/인가 실패는 GlobalExceptionHandler까지
	 * 도달하지 않으므로, 여기서 직접 동일한 형식의 JSON을 만들어 내려줍니다.
	 */
	private void writeErrorResponse(HttpServletResponse response, int status, String code,
		String message, String path) throws java.io.IOException {
		response.setStatus(status);
		response.setContentType("application/json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("""
			{"code":"%s","message":"%s","status":%d,"path":"%s"}""".formatted(code, message, status, path));
	}
}
