package haitai.safemask.domain.member.dto;

import haitai.safemask.domain.member.entity.Member;
import java.time.LocalDateTime;

public record AdminMemberResponse(
	Long id,
	String loginId,
	String name,
	String email,
	String department,
	String role,
	String approvalStatus,
	LocalDateTime createdAt,
	String reviewedByName,
	LocalDateTime reviewedAt
) {
	public static AdminMemberResponse from(Member member) {
		return new AdminMemberResponse(member.getId(), member.getLoginId(), member.getName(),
			member.getEmail(), member.getDepartment(), member.getRole().name(),
			member.getApprovalStatus().name(), member.getCreatedAt(),
			member.getReviewedByName(), member.getReviewedAt());
	}
}
