package haitai.safemask.domain.auth.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 로그인/회원가입 화면(Thymeleaf)을 렌더링하는 컨트롤러입니다.
 *
 * 화면 렌더링만 담당하고, 실제 인증 처리는 화면의 JS(fetch)가
 * AuthController의 REST API(/api/auth/**)를 호출하는 방식으로 분리했습니다.
 */
@Controller
public class AuthViewController {

	/**
	 * 루트 접근 시 로그인 화면으로 보냅니다.
	 * JWT는 브라우저 저장소에 있어 서버 렌더링 단계에서는 로그인 여부를 신뢰성 있게 판단할 수 없습니다.
	 * 로그인 화면의 JS가 유효한 장기 로그인 정보를 확인한 뒤 채팅 화면으로 이동합니다.
	 */
	@GetMapping("/")
	public String home() {
		return "redirect:/login";
	}

	/** 로그인 화면 */
	@GetMapping("/login")
	public String loginPage() {
		return "auth/login";
	}

	/** 단계별 회원가입 화면 */
	@GetMapping("/signup")
	public String signupPage() {
		return "auth/signup";
	}

	@GetMapping("/account/pending")
	public String approvalPendingPage(Model model) {
		model.addAttribute("rejected", false);
		model.addAttribute("statusTitle", "아직 승인 대기 중입니다");
		model.addAttribute("statusMessage",
			"담당 관리자가 가입 신청을 확인하고 있습니다. 승인 완료 후 SafeMask에 로그인할 수 있습니다.");
		return "auth/account-status";
	}

	@GetMapping("/account/rejected")
	public String approvalRejectedPage(Model model) {
		model.addAttribute("rejected", true);
		model.addAttribute("statusTitle", "현재 사용할 수 없는 계정입니다");
		model.addAttribute("statusMessage",
			"가입 승인 상태에 대한 자세한 내용은 IT 개발팀에 문의해 주세요.");
		return "auth/account-status";
	}
}
