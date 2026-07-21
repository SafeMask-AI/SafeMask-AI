package haitai.safemask.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.auth.repository.RefreshTokenRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.enums.MemberRole;
import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminMemberServiceTest {
	@Mock MemberRepository members;
	@Mock RefreshTokenRepository refreshTokens;

	@Test void rejectRecordsAuditAndRevokesRefreshToken() {
		Member reviewer = member(10L, "관리자", MemberApprovalStatus.APPROVED);
		Member target = member(20L, "신청자", MemberApprovalStatus.PENDING);
		when(members.findByIdForUpdate(20L)).thenReturn(Optional.of(target));
		var response = new AdminMemberService(members, refreshTokens).reject(20L, reviewer);
		assertThat(response.approvalStatus()).isEqualTo("REJECTED");
		assertThat(response.reviewedByName()).isEqualTo("관리자");
		assertThat(response.reviewedAt()).isNotNull();
		verify(refreshTokens).deleteByMember(target);
	}

	@Test
	void allMembersIncludesAdminButStatusCountsExcludeAdmin() {
		Member administrator = member(10L, "관리자", MemberApprovalStatus.APPROVED, MemberRole.ADMIN);
		Member pendingMember = member(20L, "신청자", MemberApprovalStatus.PENDING, MemberRole.USER);
		when(members.countSearchResults(null, null)).thenReturn(2L);
		when(members.searchPage(null, null, 0L, 20L)).thenReturn(List.of(administrator, pendingMember));
		when(members.count()).thenReturn(2L);
		when(members.countByApprovalStatusAndRoleNot(MemberApprovalStatus.PENDING, MemberRole.ADMIN)).thenReturn(1L);
		when(members.countByApprovalStatusAndRoleNot(MemberApprovalStatus.APPROVED, MemberRole.ADMIN)).thenReturn(0L);
		when(members.countByApprovalStatusAndRoleNot(MemberApprovalStatus.REJECTED, MemberRole.ADMIN)).thenReturn(0L);

		var response = new AdminMemberService(members, refreshTokens).search("ALL", null, 0, 20);

		assertThat(response.members()).extracting("role").containsExactly("ADMIN", "USER");
		assertThat(response.totalMemberCount()).isEqualTo(2L);
		assertThat(response.pendingCount()).isEqualTo(1L);
		assertThat(response.approvedCount()).isZero();
	}

	@Test
	void administratorApprovalStatusCannotBeChanged() {
		Member reviewer = member(10L, "검토자", MemberApprovalStatus.APPROVED, MemberRole.ADMIN);
		Member administrator = member(20L, "다른 관리자", MemberApprovalStatus.APPROVED, MemberRole.ADMIN);
		when(members.findByIdForUpdate(20L)).thenReturn(Optional.of(administrator));

		assertThatThrownBy(() -> new AdminMemberService(members, refreshTokens).reject(20L, reviewer))
			.isInstanceOfSatisfying(CustomException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CANNOT_REVIEW_ADMIN));
		verify(refreshTokens, never()).deleteByMember(administrator);
	}

	private Member member(Long id, String name, MemberApprovalStatus status) {
		return member(id, name, status, MemberRole.USER);
	}

	private Member member(Long id, String name, MemberApprovalStatus status, MemberRole role) {
		Member member = Member.create("id" + id, "encoded", name, id + "@haitai.co.kr", "개발팀");
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "approvalStatus", status);
		ReflectionTestUtils.setField(member, "role", role);
		return member;
	}
}
