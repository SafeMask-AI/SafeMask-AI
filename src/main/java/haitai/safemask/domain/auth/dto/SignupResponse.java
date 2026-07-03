package haitai.safemask.domain.auth.dto;

import haitai.safemask.domain.member.entity.Member;

/**
 * 회원가입 성공 응답 DTO입니다.
 *
 * 가입 직후 자동 로그인은 시키지 않고(토큰 미발급),
 * 프론트가 로그인 화면으로 이동해 정상 로그인 흐름을 타도록 합니다.
 */
public record SignupResponse(
	Long memberId,
	String loginId,
	String name
) {

	public static SignupResponse from(Member member) {
		return new SignupResponse(member.getId(), member.getLoginId(), member.getName());
	}
}
