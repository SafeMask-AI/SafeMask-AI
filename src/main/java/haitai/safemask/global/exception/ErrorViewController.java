package haitai.safemask.global.exception;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Security 필터에서 MVC 처리 전에 판정된 브라우저용 오류 화면을 렌더링합니다. */
@Controller
public class ErrorViewController {

	@GetMapping("/error/not-found")
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public String notFound(HttpServletRequest request, Model model) {
		Object originalPath = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
		model.addAttribute("path", originalPath == null ? request.getRequestURI() : originalPath.toString());
		return "error/404";
	}
}
