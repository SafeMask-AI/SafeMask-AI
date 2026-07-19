package haitai.safemask.global.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
		when(tokenProvider.validateToken("valid-token")).thenReturn(true);
		when(tokenProvider.getMemberIdFromToken("valid-token")).thenReturn(1L);
		when(memberRepository.findById(1L)).thenReturn(Optional.of(member));
		when(member.getRole()).thenReturn(MemberRole.USER);

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
