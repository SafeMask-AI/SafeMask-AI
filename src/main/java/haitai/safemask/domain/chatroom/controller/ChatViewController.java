package haitai.safemask.domain.chatroom.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 채팅 메인 화면(Thymeleaf)을 렌더링하는 컨트롤러입니다.
 *
 * 화면 접근 자체는 열어두고(permitAll), 로그인 여부는 화면의 JS(chat.js)가
 * localStorage의 accessToken 존재 여부로 확인해 미로그인 시 /login으로 돌려보냅니다.
 * 실제 데이터 API(/api/**)는 JWT 필터가 보호하므로 화면만 봐서는 아무것도 조회할 수 없습니다.
 */
@Controller
public class ChatViewController {

	/** 채팅 메인 화면 (로그인 성공 후 진입) */
	@GetMapping("/chat")
	public String chatPage() {
		return "chat/main";
	}
}
