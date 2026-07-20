package haitai.safemask.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.auth.dto.LoginRequest;
import haitai.safemask.domain.auth.repository.RefreshTokenRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import haitai.safemask.global.jwt.JwtProperties;
import haitai.safemask.global.jwt.JwtTokenProvider;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceApprovalTest {
	@Mock MemberRepository members;
	@Mock RefreshTokenRepository refreshTokens;
	@Mock PasswordEncoder passwords;
	@Mock JwtTokenProvider jwt;
	private AuthService service;

	@BeforeEach void setUp() {
		service = new AuthService(members, refreshTokens, passwords, jwt, new JwtProperties());
	}

	@Test void pendingMemberCannotReceiveTokenAfterValidPassword() {
		Member member = member(MemberApprovalStatus.PENDING);
		when(members.findByLoginId("2026001")).thenReturn(Optional.of(member));
		when(passwords.matches("Password1", member.getPassword())).thenReturn(true);
		assertThatThrownBy(() -> service.login(new LoginRequest("2026001", "Password1", false)))
			.isInstanceOf(CustomException.class)
			.satisfies(error -> assertThat(((CustomException) error).getErrorCode())
				.isEqualTo(ErrorCode.APPROVAL_PENDING));
		verify(jwt, never()).createAccessToken(member.getId());
		verify(refreshTokens, never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test void rejectedMemberCannotReceiveTokenAfterValidPassword() {
		Member member = member(MemberApprovalStatus.REJECTED);
		when(members.findByLoginId("2026001")).thenReturn(Optional.of(member));
		when(passwords.matches("Password1", member.getPassword())).thenReturn(true);
		assertThatThrownBy(() -> service.login(new LoginRequest("2026001", "Password1", false)))
			.satisfies(error -> assertThat(((CustomException) error).getErrorCode())
				.isEqualTo(ErrorCode.APPROVAL_REJECTED));
		verify(jwt, never()).createAccessToken(member.getId());
	}

	private Member member(MemberApprovalStatus status) {
		Member member = Member.create("2026001", "encoded", "홍길동", "hong@haitai.co.kr", "개발팀");
		ReflectionTestUtils.setField(member, "id", 1L);
		ReflectionTestUtils.setField(member, "approvalStatus", status);
		return member;
	}
}
