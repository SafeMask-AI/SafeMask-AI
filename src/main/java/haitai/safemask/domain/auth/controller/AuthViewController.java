package haitai.safemask.domain.auth.controller;

import org.springframework.stereotype.Controller;
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
	 * TODO: 메인(채팅) 화면이 생기면 로그인 여부에 따라 분기하도록 변경하세요.
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
}
