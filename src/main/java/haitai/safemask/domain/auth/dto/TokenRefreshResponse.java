package haitai.safemask.domain.auth.dto;

import haitai.safemask.domain.member.entity.Member;

/**
 * 토큰 갱신 성공 응답 DTO입니다.
 *
 * 응답 본문에는 새 Access Token과 현재 DB 기준 회원 표시·권한 정보를 담습니다.
 * 새 Refresh Token은 rotation되어 HttpOnly 쿠키로 교체 발급되므로
 * 클라이언트가 별도로 저장할 필요가 없습니다.
 */
public record TokenRefreshResponse(
	String accessToken,
	String name,
	String department,
	String role
) {
	public static TokenRefreshResponse of(Member member, String accessToken) {
		return new TokenRefreshResponse(accessToken, member.getName(), member.getDepartment(),
			member.getRole().name());
	}
}
