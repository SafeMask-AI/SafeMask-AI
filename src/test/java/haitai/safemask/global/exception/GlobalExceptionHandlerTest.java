package haitai.safemask.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

class GlobalExceptionHandlerTest {

	@Test
	void htmlErrorViewUsesItsRealHttpStatus() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/missing-page");
		request.addHeader("Accept", "text/html");
		MockHttpServletResponse response = new MockHttpServletResponse();
		ExtendedModelMap model = new ExtendedModelMap();

		Object view = new GlobalExceptionHandler().handleCustomException(
			new CustomException(ErrorCode.NOT_FOUND), request, response, model);

		assertThat(view).isEqualTo("error/404");
		assertThat(response.getStatus()).isEqualTo(404);
		assertThat(model.get("status")).isEqualTo(404);
	}
}
