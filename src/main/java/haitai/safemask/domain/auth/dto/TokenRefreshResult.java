package haitai.safemask.domain.auth.dto;

/**
 * 토큰 갱신 처리 결과를 서비스에서 컨트롤러로 전달하는 내부용 DTO입니다.
 * 새 Refresh Token은 컨트롤러가 HttpOnly 쿠키로 내려주고,
	 * 응답 본문용 현재 회원 정보와 새 Access Token을 함께 전달합니다.
 */
public record TokenRefreshResult(
	TokenRefreshResponse response,
	String refreshToken
) {
}
