package haitai.safemask.global.jwt;

import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.repository.MemberRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 매 요청마다 Authorization 헤더의 JWT를 검사해 인증 정보를 세팅하는 필터입니다.
 *
 * 동작 흐름:
 * 1. "Authorization: Bearer {token}" 헤더에서 토큰을 꺼낸다.
 * 2. 토큰의 서명/만료를 검증하고 memberId를 추출한다.
 * 3. DB에서 현재도 승인된 회원인지 확인해 SecurityContext에 인증 객체를 등록한다.
 *
 * 토큰이 없거나 유효하지 않으면 예외를 던지지 않고 SecurityContext를 비운 채
 * 다음 필터로 넘깁니다. 이후 SecurityConfig의 AuthenticationEntryPoint가
 * 인증이 필요한 경로에 대해 401 응답을 내려줍니다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String AUTHORIZATION_HEADER = "Authorization";
	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtTokenProvider jwtTokenProvider;
	private final MemberRepository memberRepository;

	public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, MemberRepository memberRepository) {
		this.jwtTokenProvider = jwtTokenProvider;
		this.memberRepository = memberRepository;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain) throws ServletException, IOException {

		String token = resolveToken(request);

		if (token != null) {
			try {
				if (jwtTokenProvider.validateToken(token)) {
					Long memberId = jwtTokenProvider.getMemberIdFromToken(token);

					// 토큰 발급 이후 회원이 삭제됐을 수 있으므로 매 요청마다 DB에서 확인합니다.
					memberRepository.findById(memberId).filter(Member::isApproved).ifPresent(member -> {
						// Spring Security의 hasRole("ADMIN") 검사는 "ROLE_" 접두사를 기준으로 동작합니다.
						List<SimpleGrantedAuthority> authorities =
							List.of(new SimpleGrantedAuthority("ROLE_" + member.getRole().name()));

						// 승인 취소는 DB 상태를 매 요청 다시 확인하므로 기존 Access Token에도 즉시 반영됩니다.
						// principal에 Member 엔티티를 담아, 컨트롤러에서
						// @AuthenticationPrincipal Member로 현재 로그인 회원을 바로 꺼낼 수 있게 합니다.
						UsernamePasswordAuthenticationToken authentication =
							new UsernamePasswordAuthenticationToken(member, null, authorities);

						SecurityContextHolder.getContext().setAuthentication(authentication);
					});
				}
			} catch (Exception e) {
				// 만료·서명 오류 등 모든 JWT 예외는 "인증 실패"로만 처리합니다.
				// 여기서 예외를 전파하면 permitAll 경로까지 막히므로,
				// SecurityContext만 비우고 다음 필터로 넘깁니다.
				SecurityContextHolder.clearContext();
			}
		}

		filterChain.doFilter(request, response);
	}

	/**
	 * Authorization 헤더에서 "Bearer " 접두사를 제거하고 순수 토큰 문자열만 꺼냅니다.
	 * 헤더가 없거나 형식이 다르면 null을 반환해 비로그인 요청으로 처리합니다.
	 */
	private String resolveToken(HttpServletRequest request) {
		String bearer = request.getHeader(AUTHORIZATION_HEADER);
		if (bearer != null && bearer.startsWith(BEARER_PREFIX)) {
			return bearer.substring(BEARER_PREFIX.length());
		}
		return null;
	}
}
