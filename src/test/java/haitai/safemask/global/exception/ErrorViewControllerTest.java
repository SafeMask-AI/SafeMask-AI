package haitai.safemask.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ErrorViewControllerTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ErrorViewController()).build();

	@Test
	void notFoundPageKeepsOriginalRequestedPath() throws Exception {
		mockMvc.perform(get("/error/not-found")
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/admin/213124521421"))
			.andExpect(status().isNotFound())
			.andExpect(view().name("error/404"))
			.andExpect(model().attribute("path", "/admin/213124521421"));
	}
}
