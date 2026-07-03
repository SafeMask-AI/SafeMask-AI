package haitai.safemask.domain.auth.dto;

/**
 * 토큰 갱신 처리 결과를 서비스에서 컨트롤러로 전달하는 내부용 DTO입니다.
 * 새 Refresh Token은 컨트롤러가 HttpOnly 쿠키로 내려주고,
 * 응답 본문에는 accessToken만 담습니다.
 */
public record TokenRefreshResult(
	String accessToken,
	String refreshToken
) {
}
