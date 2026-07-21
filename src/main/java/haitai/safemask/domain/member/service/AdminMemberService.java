package haitai.safemask.domain.member.service;

import haitai.safemask.domain.auth.repository.RefreshTokenRepository;
import haitai.safemask.domain.member.dto.AdminMemberPageResponse;
import haitai.safemask.domain.member.dto.AdminMemberResponse;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.enums.MemberRole;
import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@Slf4j
public class AdminMemberService {

	private static final int MAX_PAGE_SIZE = 50;
	private final MemberRepository memberRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	public AdminMemberService(MemberRepository memberRepository,
		RefreshTokenRepository refreshTokenRepository) {
		this.memberRepository = memberRepository;
		this.refreshTokenRepository = refreshTokenRepository;
	}

	public AdminMemberPageResponse search(String rawStatus, String rawKeyword, int page, int size) {
		MemberApprovalStatus status = parseStatus(rawStatus);
		String keyword = rawKeyword == null || rawKeyword.isBlank() ? null : rawKeyword.trim();
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		long startRow = (long) safePage * safeSize;
		long endRow = startRow + safeSize;
		String statusValue = status == null ? null : status.name();
		long totalElements = memberRepository.countSearchResults(statusValue, keyword);
		var members = memberRepository.searchPage(statusValue, keyword, startRow, endRow);
		int totalPages = totalElements == 0 ? 0
			: (int) Math.min(Integer.MAX_VALUE, (totalElements + safeSize - 1) / safeSize);

		return new AdminMemberPageResponse(
			members.stream().map(AdminMemberResponse::from).toList(),
			totalElements, totalPages, safePage, safeSize,
			memberRepository.count(),
			memberRepository.countByApprovalStatusAndRoleNot(MemberApprovalStatus.PENDING, MemberRole.ADMIN),
			memberRepository.countByApprovalStatusAndRoleNot(MemberApprovalStatus.APPROVED, MemberRole.ADMIN),
			memberRepository.countByApprovalStatusAndRoleNot(MemberApprovalStatus.REJECTED, MemberRole.ADMIN));
	}

	@Transactional
	public AdminMemberResponse approve(Long memberId, Member reviewer) {
		return review(memberId, reviewer, MemberApprovalStatus.APPROVED);
	}

	@Transactional
	public AdminMemberResponse reject(Long memberId, Member reviewer) {
		return review(memberId, reviewer, MemberApprovalStatus.REJECTED);
	}

	private AdminMemberResponse review(Long memberId, Member reviewer, MemberApprovalStatus target) {
		if (reviewer.getId().equals(memberId)) {
			throw new CustomException(ErrorCode.CANNOT_REVIEW_SELF);
		}
		Member member = memberRepository.findByIdForUpdate(memberId)
			.orElseThrow(() -> new CustomException(ErrorCode.MEMBER_NOT_FOUND));
		if (member.getRole() == MemberRole.ADMIN) {
			throw new CustomException(ErrorCode.CANNOT_REVIEW_ADMIN);
		}
		if (member.getApprovalStatus() == target) {
			throw new CustomException(ErrorCode.INVALID_APPROVAL_TRANSITION);
		}
		member.review(target, reviewer);
		// 거절된 회원은 토큰 갱신이 불가능하도록 장기 세션도 즉시 폐기합니다.
		if (target == MemberApprovalStatus.REJECTED) {
			refreshTokenRepository.deleteByMember(member);
		}
		return AdminMemberResponse.from(member);
	}

	private MemberApprovalStatus parseStatus(String rawStatus) {
		if (rawStatus == null || rawStatus.isBlank() || "ALL".equalsIgnoreCase(rawStatus)) {
			return null;
		}
		try {
			return MemberApprovalStatus.valueOf(rawStatus.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}

	/**
	 * 운영자가 지정한 기존 회원을 최초 관리자로 승격합니다.
	 * 관리자가 이미 한 명이라도 있으면 설정값을 무시해 재기동에 의한 권한 확대를 막습니다.
	 */
	@Transactional
	public void bootstrapFirstAdmin(String loginId) {
		if (loginId == null || loginId.isBlank() || memberRepository.countByRole(MemberRole.ADMIN) > 0) {
			return;
		}
		memberRepository.findByLoginId(loginId.trim()).ifPresentOrElse(member -> {
			member.bootstrapAdmin();
			log.info("Bootstrapped the first SafeMask administrator for memberId={}", member.getId());
		}, () -> log.warn("Admin bootstrap loginId does not match an existing member"));
	}
}
