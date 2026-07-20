package haitai.safemask.domain.member.dto;

import java.util.List;

public record AdminMemberPageResponse(
	List<AdminMemberResponse> members,
	long totalElements,
	int totalPages,
	int page,
	int size,
	long pendingCount,
	long approvedCount,
	long rejectedCount
) {
}
