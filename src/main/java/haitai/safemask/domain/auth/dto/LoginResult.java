package haitai.safemask.domain.auth.dto;

/**
 * 로그인/토큰 갱신 처리 결과를 서비스에서 컨트롤러로 전달하는 내부용 DTO입니다.
 *
 * Refresh Token은 클라이언트 JS가 읽지 못하도록 응답 본문(JSON)이 아닌
 * HttpOnly 쿠키로 내려야 하므로, 본문용 응답(response)과 분리해서 전달합니다.
 * 쿠키 세팅은 컨트롤러의 책임이라 이 객체는 API 응답으로 직접 나가지 않습니다.
 */
public record LoginResult(
	LoginResponse response,
	String refreshToken
) {
}
