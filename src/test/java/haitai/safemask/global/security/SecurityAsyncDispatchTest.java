package haitai.safemask.global.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberRole;
import haitai.safemask.domain.member.repository.MemberRepository;
import haitai.safemask.global.jwt.JwtTokenProvider;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class SecurityAsyncDispatchTest {

	private AnnotationConfigWebApplicationContext context;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
		MemberRepository memberRepository = mock(MemberRepository.class);
		Member member = mock(Member.class);
		Member administrator = mock(Member.class);
		when(tokenProvider.validateToken("valid-token")).thenReturn(true);
		when(tokenProvider.getMemberIdFromToken("valid-token")).thenReturn(1L);
		when(tokenProvider.validateToken("admin-token")).thenReturn(true);
		when(tokenProvider.getMemberIdFromToken("admin-token")).thenReturn(2L);
		when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
		when(memberRepository.findById(2L)).thenReturn(Optional.of(administrator));
		when(member.isApproved()).thenReturn(true);
		when(member.getRole()).thenReturn(MemberRole.USER);
		when(administrator.isApproved()).thenReturn(true);
		when(administrator.getRole()).thenReturn(MemberRole.ADMIN);

		context = new AnnotationConfigWebApplicationContext();
		context.setServletContext(new MockServletContext());
		TestDependencies.tokenProvider = tokenProvider;
		TestDependencies.memberRepository = memberRepository;
		context.register(SecurityConfig.class, AsyncControllerConfig.class, TestDependencies.class);
		context.refresh();

		mockMvc = MockMvcBuilders.webAppContextSetup(context)
			.apply(springSecurity())
			.build();
	}

	@AfterEach
	void tearDown() {
		context.close();
	}

	@Test
	void authenticatedAsyncRequestCanCompleteItsFollowUpDispatch() throws Exception {
		MvcResult initial = mockMvc.perform(get("/test/async")
				.header("Authorization", "Bearer valid-token"))
			.andExpect(request().asyncStarted())
			.andReturn();

		mockMvc.perform(asyncDispatch(initial))
			.andExpect(status().isOk());
	}

	@Test
	void unauthenticatedInitialRequestIsStillRejected() throws Exception {
		mockMvc.perform(get("/test/async"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedApiRequestKeepsJson401Contract() throws Exception {
		mockMvc.perform(get("/api/private-resource").accept(MediaType.TEXT_HTML))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("COMMON_401"));
	}

	@Test
	void unknownBrowserNavigationForwardsToNotFoundPage() throws Exception {
		mockMvc.perform(get("/admin/not-existing-page").accept(MediaType.TEXT_HTML))
			.andExpect(status().isNotFound())
			.andExpect(forwardedUrl("/error/not-found"));
	}

	@Test
	void administratorCannotUseChatOrFileApis() throws Exception {
		mockMvc.perform(get("/api/chat/test")
				.header("Authorization", "Bearer admin-token"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/files/test")
				.header("Authorization", "Bearer admin-token"))
			.andExpect(status().isForbidden());
	}

	@Test
	void userCannotUseAdminApiButAdministratorCan() throws Exception {
		mockMvc.perform(get("/api/admin/test")
				.header("Authorization", "Bearer valid-token"))
			.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/test")
				.header("Authorization", "Bearer admin-token"))
			.andExpect(status().isOk());
	}

	@EnableWebMvc
	static class AsyncControllerConfig {

		@Bean
		AsyncController asyncController() {
			return new AsyncController();
		}

		@RestController
		static class AsyncController {

			@GetMapping("/test/async")
			Callable<String> async() {
				return () -> "ok";
			}

			@GetMapping({"/api/chat/test", "/api/files/test", "/api/admin/test"})
			String protectedResource() {
				return "ok";
			}
		}
	}

	@Configuration
	static class TestDependencies {

		private static JwtTokenProvider tokenProvider;
		private static MemberRepository memberRepository;

		@Bean
		JwtTokenProvider jwtTokenProvider() {
			return tokenProvider;
		}

		@Bean
		MemberRepository memberRepository() {
			return memberRepository;
		}
	}
}
