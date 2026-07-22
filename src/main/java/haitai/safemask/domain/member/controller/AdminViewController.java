package haitai.safemask.domain.member.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminViewController {

	@GetMapping("/admin")
	public String adminPage() {
		return "admin/members";
	}

	@GetMapping("/admin/rules")
	public String maskingRulesPage() {
		return "admin/rules";
	}
}
