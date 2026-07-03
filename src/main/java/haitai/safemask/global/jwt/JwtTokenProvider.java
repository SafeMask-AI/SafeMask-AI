package haitai.safemask.global.jwt;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import java.security.Key;
import java.util.Date;
import org.springframework.stereotype.Component;

/**
 * JWT Access Token의 생성, 파싱, 검증을 담당하는 컴포넌트입니다.
 *
 * JwtProperties에 바인딩된 시크릿과 만료시간을 기반으로
 * Access Token을 생성하고, 전달받은 토큰의 서명/만료 여부를 검증하며,
 * 토큰 내부의 회원 식별자(memberId)를 추출합니다.
 *
 * Refresh Token은 JWT가 아닌 UUID 문자열을 DB에 저장하는 방식이므로
 * 이 클래스가 아닌 AuthService에서 발급/관리합니다.
 */
@Component
public class JwtTokenProvider {

	private final JwtProperties jwtProperties;

	/** JWT 서명/검증에 사용할 HMAC-SHA 키 (시크릿 문자열로부터 생성) */
	private Key key;

	public JwtTokenProvider(JwtProperties jwtProperties) {
		this.jwtProperties = jwtProperties;
	}

	@PostConstruct
	void init() {
		// .env의 JWT_SECRET 문자열을 HMAC-SHA256 키 객체로 변환합니다.
		// 시크릿이 32바이트 미만이면 여기서 WeakKeyException이 발생해 애플리케이션 기동이 실패합니다.
		this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes());
	}

	/**
	 * Access Token을 생성합니다.
	 *
	 * - subject(주체)에 memberId를 담아 "누구의 토큰인지"를 표현합니다.
	 * - 발급 시각(iat)과 만료 시각(exp)을 설정합니다.
	 * - HS256 알고리즘과 시크릿 키로 서명하여 위·변조를 방지합니다.
	 */
	public String createAccessToken(Long memberId) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + jwtProperties.getAccessTokenExpiration());

		return Jwts.builder()
			.setSubject(String.valueOf(memberId)) // 토큰 소유자(회원 PK)
			.setIssuedAt(now)                     // 발급 시각
			.setExpiration(expiry)                // 만료 시각
			.signWith(key, SignatureAlgorithm.HS256)
			.compact();                           // 최종 문자열(header.payload.signature)로 직렬화
	}

	/**
	 * 토큰에서 회원 식별자(subject)를 추출합니다.
	 * 검증되지 않은 토큰에서 호출하면 안 되므로, 항상 validateToken() 이후에 사용합니다.
	 */
	public Long getMemberIdFromToken(String token) {
		Claims claims = parseClaims(token);
		return Long.valueOf(claims.getSubject());
	}

	/**
	 * 토큰 유효성을 검증합니다.
	 *
	 * 서명 불일치, 형식 오류, 만료 등의 jjwt 예외를 잡아
	 * 프로젝트 공통 예외(CustomException + ErrorCode)로 변환해 던집니다.
	 * 만료는 클라이언트가 refresh 흐름으로 분기해야 하므로 별도 코드로 구분합니다.
	 */
	public boolean validateToken(String token) {
		try {
			parseClaims(token);
			return true;
		} catch (ExpiredJwtException e) {
			throw new CustomException(ErrorCode.EXPIRED_TOKEN);
		} catch (JwtException | IllegalArgumentException e) {
			// 서명 불일치, 형식 오류, 빈 토큰 등 만료 외의 모든 JWT 오류
			throw new CustomException(ErrorCode.INVALID_TOKEN);
		}
	}

	/**
	 * 실제 Claims 파싱을 수행합니다.
	 * parseClaimsJws()가 내부적으로 서명 검증과 만료 확인을 함께 수행하며,
	 * 문제가 있으면 jjwt 예외를 던집니다.
	 */
	private Claims parseClaims(String token) {
		return Jwts.parserBuilder()
			.setSigningKey(key)    // 서명 검증에 사용할 키
			.build()
			.parseClaimsJws(token) // JWS 파싱 + 서명/만료 검증
			.getBody();            // payload(claims) 추출
	}
}
