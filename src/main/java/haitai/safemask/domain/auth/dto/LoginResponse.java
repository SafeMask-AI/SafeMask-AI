package haitai.safemask.domain.auth.dto;

import haitai.safemask.domain.member.entity.Member;

/**
 * 로그인 성공 응답 DTO입니다.
 *
 * - accessToken: API 호출 시 Authorization 헤더에 담아 보내는 JWT
 * - Refresh Token은 XSS로 탈취되지 않도록 응답 본문이 아닌
 *   HttpOnly 쿠키로 내려갑니다. (AuthController에서 Set-Cookie 처리)
 * - 이름/부서/권한은 로그인 직후 화면 표시용으로 함께 내려줍니다.
 */
public record LoginResponse(
	Long memberId,
	String name,
	String department,
	String role,
	String accessToken
) {

	public static LoginResponse of(Member member, String accessToken) {
		return new LoginResponse(
			member.getId(),
			member.getName(),
			member.getDepartment(),
			member.getRole().name(),
			accessToken
		);
	}
}
