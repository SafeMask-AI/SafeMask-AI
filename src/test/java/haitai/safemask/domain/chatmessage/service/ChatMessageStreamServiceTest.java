package haitai.safemask.domain.chatmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatMessageStreamServiceTest {

	@Test
	void expectedPreparationFailureIsDeliveredAsSseErrorInsteadOfHttp500() throws Exception {
		ChatMessageService chatMessageService = mock(ChatMessageService.class);
		when(chatMessageService.prepare(isNull(), any(ChatSendRequest.class)))
			.thenThrow(new CustomException(ErrorCode.INVALID_REQUEST,
				"첨부 파일에서 추출된 내용이 허용량을 초과했습니다."));
		ChatMessageStreamService streamService = new ChatMessageStreamService(chatMessageService,
			mock(ThreadPoolTaskExecutor.class), mock(TaskScheduler.class), Duration.ofSeconds(30));
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestStreamController(streamService)).build();

		MvcResult initial = mockMvc.perform(get("/test/chat-stream").accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(request().asyncStarted())
			.andReturn();

		MvcResult completed = mockMvc.perform(asyncDispatch(initial))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
			.andReturn();

		String streamBody = new String(completed.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
		assertThat(streamBody).contains("event:error", "허용량을 초과했습니다");
	}

	@RestController
	private static final class TestStreamController {

		private final ChatMessageStreamService streamService;

		private TestStreamController(ChatMessageStreamService streamService) {
			this.streamService = streamService;
		}

		@GetMapping(value = "/test/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
		SseEmitter stream() {
			return streamService.send(null, new ChatSendRequest(null, "질문", null, null));
		}
	}
}
