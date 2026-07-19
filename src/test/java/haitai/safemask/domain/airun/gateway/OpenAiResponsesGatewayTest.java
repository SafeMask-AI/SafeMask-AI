package haitai.safemask.domain.airun.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenAiResponsesGatewayTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private HttpServer server;
	private URI endpoint;
	private AtomicReference<String> requestBody;
	private AtomicReference<byte[]> responseBody;
	private AtomicBoolean holdStreamOpen;
	private CountDownLatch streamStarted;
	private CountDownLatch releaseStream;

	@BeforeEach
	void setUp() throws Exception {
		requestBody = new AtomicReference<>();
		responseBody = new AtomicReference<>(webSearchStream().getBytes(StandardCharsets.UTF_8));
		holdStreamOpen = new AtomicBoolean(false);
		streamStarted = new CountDownLatch(1);
		releaseStream = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/responses", exchange -> {
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] response = responseBody.get();
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
			if (holdStreamOpen.get()) {
				exchange.sendResponseHeaders(200, 0);
				exchange.getResponseBody().write("data: {\"type\":\"response.created\"}\n\n"
					.getBytes(StandardCharsets.UTF_8));
				exchange.getResponseBody().flush();
				streamStarted.countDown();
				try {
					releaseStream.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				exchange.close();
				return;
			}
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/responses");
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	@Test
	void sendsOnlyProvidedMaskedMessagesAndEnablesWebSearchWithoutProviderStorage() throws Exception {
		OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(objectMapper, new WebCitationFormatter(),
			"test-key", "test-model", endpoint, Duration.ofSeconds(5));
		List<AiProgressStage> stages = new ArrayList<>();

		AiGatewayResponse response = gateway.generate(List.of(
			new AiPromptMessage("system", "보안 지침"),
			new AiPromptMessage("user", "[PERSON_001] 관련 최신 자료를 확인해줘")
		), (stage, message, sources) -> stages.add(stage));

		JsonNode sent = objectMapper.readTree(requestBody.get());
		assertThat(sent.path("store").asBoolean()).isFalse();
		assertThat(sent.path("stream").asBoolean()).isTrue();
		assertThat(sent.path("tool_choice").asText()).isEqualTo("auto");
		assertThat(sent.path("tools").get(0).path("type").asText()).isEqualTo("web_search");
		assertThat(sent.toString()).contains("[PERSON_001]").doesNotContain("홍길동");
		assertThat(response.maskedText()).contains("[출처](https://example.com/report)");
		assertThat(response.inputTokens()).isEqualTo(12);
		assertThat(response.outputTokens()).isEqualTo(7);
		assertThat(stages).containsExactly(
			AiProgressStage.PREPARING,
			AiProgressStage.WEB_SEARCH_STARTED,
			AiProgressStage.WEB_SEARCHING,
			AiProgressStage.WEB_SEARCH_COMPLETED,
			AiProgressStage.DRAFTING,
			AiProgressStage.SOURCES_FOUND,
			AiProgressStage.FINALIZING);
	}

	@Test
	void doesNotReportWebSearchStagesWhenTheModelAnswersWithoutUsingTheTool() {
		responseBody.set("""
			event: response.output_text.delta
			data: {"type":"response.output_text.delta","delta":"안녕하세요!","sequence_number":1}

			event: response.completed
			data: {"type":"response.completed","sequence_number":2,"response":{"model":"test-model","output":[{"type":"message","content":[{"type":"output_text","text":"안녕하세요!","annotations":[]}]}],"usage":{"input_tokens":3,"output_tokens":2}}}

			"""
			.getBytes(StandardCharsets.UTF_8));
		OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(objectMapper, new WebCitationFormatter(),
			"test-key", "test-model", endpoint, Duration.ofSeconds(5));
		List<AiProgressStage> stages = new ArrayList<>();

		AiGatewayResponse response = gateway.generate(List.of(new AiPromptMessage("user", "안녕")),
			(stage, message, sources) -> stages.add(stage));

		assertThat(response.maskedText()).isEqualTo("안녕하세요!");
		assertThat(stages).containsExactly(
			AiProgressStage.PREPARING,
			AiProgressStage.DRAFTING,
			AiProgressStage.FINALIZING);
	}

	@Test
	void interruptingTheWorkerStopsAWaitingOpenAiStream() throws Exception {
		holdStreamOpen.set(true);
		OpenAiResponsesGateway gateway = new OpenAiResponsesGateway(objectMapper, new WebCitationFormatter(),
			"test-key", "test-model", endpoint, Duration.ofSeconds(30));
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch gatewayExited = new CountDownLatch(1);
		try {
			Future<?> future = executor.submit(() -> {
				try {
					gateway.generate(List.of(new AiPromptMessage("user", "최신 자료를 찾아줘")),
						AiProgressListener.NONE);
				} finally {
					gatewayExited.countDown();
				}
			});
			assertThat(streamStarted.await(2, TimeUnit.SECONDS)).isTrue();

			assertThat(future.cancel(true)).isTrue();
			assertThat(gatewayExited.await(2, TimeUnit.SECONDS)).isTrue();
		} finally {
			releaseStream.countDown();
			executor.shutdownNow();
		}
	}

	private String webSearchStream() {
		return """
			event: response.web_search_call.in_progress
			data: {"type":"response.web_search_call.in_progress","item_id":"ws_1","sequence_number":1}

			event: response.web_search_call.searching
			data: {"type":"response.web_search_call.searching","item_id":"ws_1","sequence_number":2}

			event: response.web_search_call.completed
			data: {"type":"response.web_search_call.completed","item_id":"ws_1","sequence_number":3}

			event: response.output_text.delta
			data: {"type":"response.output_text.delta","delta":"최신 자료입니다.","sequence_number":4}

			event: response.completed
			data: {"type":"response.completed","sequence_number":5,"response":{"model":"test-model-2026-01-01","output":[{"type":"message","content":[{"type":"output_text","text":"최신 자료입니다.","annotations":[{"type":"url_citation","start_index":0,"end_index":6,"url":"https://example.com/report","title":"공식 보고서"}]}]}],"usage":{"input_tokens":12,"output_tokens":7}}}

			""";
	}
}
