package haitai.safemask.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.auth.repository.RefreshTokenRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.enums.MemberRole;
import haitai.safemask.domain.member.repository.MemberRepository;
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

	private Member member(Long id, String name, MemberApprovalStatus status) {
		Member member = Member.create("id" + id, "encoded", name, id + "@haitai.co.kr", "개발팀");
		ReflectionTestUtils.setField(member, "id", id);
		ReflectionTestUtils.setField(member, "approvalStatus", status);
		ReflectionTestUtils.setField(member, "role", MemberRole.USER);
		return member;
	}
}
