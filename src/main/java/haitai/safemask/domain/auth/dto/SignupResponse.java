package haitai.safemask.domain.auth.dto;

import haitai.safemask.domain.member.entity.Member;

/**
 * 회원가입 성공 응답 DTO입니다.
 *
 * 가입 직후 자동 로그인은 시키지 않고(토큰 미발급),
 * 프론트가 관리자 승인 대기 안내를 표시하도록 승인 상태를 함께 반환합니다.
 */
public record SignupResponse(
	Long memberId,
	String loginId,
	String name,
	String approvalStatus
) {

	public static SignupResponse from(Member member) {
		return new SignupResponse(member.getId(), member.getLoginId(), member.getName(),
			member.getApprovalStatus().name());
	}
}
