package haitai.safemask.domain.member.controller;

import haitai.safemask.domain.member.dto.AdminMemberPageResponse;
import haitai.safemask.domain.member.dto.AdminMemberResponse;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.service.AdminMemberService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/members")
public class AdminMemberController {

	private final AdminMemberService adminMemberService;

	public AdminMemberController(AdminMemberService adminMemberService) {
		this.adminMemberService = adminMemberService;
	}

	@GetMapping
	public AdminMemberPageResponse search(
		@RequestParam(defaultValue = "PENDING") String status,
		@RequestParam(required = false) String keyword,
		@RequestParam(defaultValue = "0") int page,
		@RequestParam(defaultValue = "20") int size
	) {
		return adminMemberService.search(status, keyword, page, size);
	}

	@PostMapping("/{memberId}/approve")
	public AdminMemberResponse approve(@PathVariable Long memberId,
		@AuthenticationPrincipal Member reviewer) {
		return adminMemberService.approve(memberId, reviewer);
	}

	@PostMapping("/{memberId}/reject")
	public AdminMemberResponse reject(@PathVariable Long memberId,
		@AuthenticationPrincipal Member reviewer) {
		return adminMemberService.reject(memberId, reviewer);
	}
}
