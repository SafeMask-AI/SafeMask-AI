package haitai.safemask.domain.airun.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import haitai.safemask.domain.airun.gateway.WebCitationFormatter.FormattedText;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** OpenAI Responses API와 hosted web_search 도구를 사용하는 운영 AI 게이트웨이입니다. */
@Component
public class OpenAiResponsesGateway implements AiGateway {

	private final ObjectMapper objectMapper;
	private final WebCitationFormatter citationFormatter;
	private final HttpClient httpClient;
	private final URI responsesUri;
	private final Duration requestTimeout;
	private final String apiKey;
	private final String model;

	public OpenAiResponsesGateway(ObjectMapper objectMapper, WebCitationFormatter citationFormatter,
		@Value("${spring.ai.openai.api-key:}") String apiKey,
		@Value("${safemask.ai.model:gpt-5.5}") String model,
		@Value("${safemask.ai.responses-url:https://api.openai.com/v1/responses}") URI responsesUri,
		@Value("${safemask.ai.request-timeout:180s}") Duration requestTimeout) {
		this.objectMapper = objectMapper;
		this.citationFormatter = citationFormatter;
		this.apiKey = apiKey;
		this.model = model;
		this.responsesUri = responsesUri;
		this.requestTimeout = requestTimeout;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	@Override
	public AiGatewayResponse generate(List<AiPromptMessage> messages, AiProgressListener progressListener) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new AiGatewayException("OpenAI API key is not configured.");
		}
		AiProgressListener listener = progressListener == null ? AiProgressListener.NONE : progressListener;
		listener.onProgress(AiProgressStage.PREPARING, "질문을 이해하고 필요한 작업을 판단하고 있어요.", List.of());

		HttpRequest request = HttpRequest.newBuilder(responsesUri)
			.timeout(requestTimeout)
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(messages)))
			.build();

		try {
			HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = httpClient
				.sendAsync(request, HttpResponse.BodyHandlers.ofPublisher())
				.get();
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				cancelPublisher(response.body());
				// 공급자 응답 본문에는 요청 세부정보가 포함될 수 있어 로그·예외 메시지로 전달하지 않습니다.
				throw new AiGatewayException("OpenAI Responses API returned HTTP " + response.statusCode());
			}
			AiGatewayResponse parsed = readStreamingResponse(response.body(), listener);
			if (!parsed.sources().isEmpty()) {
				listener.onProgress(AiProgressStage.SOURCES_FOUND,
					"웹 출처 " + parsed.sources().size() + "개를 확인했습니다.", parsed.sources());
			}
			listener.onProgress(AiProgressStage.FINALIZING, "답변을 안전하게 복원하고 정리하는 중이에요.", List.of());
			return parsed;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiGatewayException("OpenAI request was interrupted.", e);
		} catch (ExecutionException e) {
			throw new AiGatewayException("OpenAI request failed.", e.getCause());
		} catch (IOException e) {
			if (e instanceof InterruptedIOException) {
				Thread.currentThread().interrupt();
				throw new AiGatewayException("OpenAI request was interrupted.", e);
			}
			throw new AiGatewayException("OpenAI request failed.", e);
		}
	}

	private void cancelPublisher(Flow.Publisher<List<ByteBuffer>> publisher) {
		publisher.subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.cancel();
			}

			@Override
			public void onNext(List<ByteBuffer> item) {
			}

			@Override
			public void onError(Throwable throwable) {
			}

			@Override
			public void onComplete() {
			}
		});
	}

	private String buildRequestBody(List<AiPromptMessage> messages) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("model", model);
		// SafeMask는 자체 DB에서 대화 상태를 관리하므로 공급자 측 응답 저장을 명시적으로 끕니다.
		root.put("store", false);
		// 답변 델타는 외부로 바로 노출하지 않고, 도구 실행 진행 이벤트만 사용자 상태 표시에 사용합니다.
		// 최종 본문은 response.completed를 전부 받은 뒤 원복·파일 처리를 거쳐 한 번에 전달합니다.
		root.put("stream", true);
		root.put("tool_choice", "auto");
		ArrayNode tools = root.putArray("tools");
		tools.addObject().put("type", "web_search");
		root.putArray("include").add("web_search_call.action.sources");

		ArrayNode input = root.putArray("input");
		for (AiPromptMessage message : messages) {
			if (message == null || message.content() == null || message.content().isBlank()) {
				continue;
			}
			ObjectNode item = input.addObject();
			item.put("role", message.role());
			item.put("content", message.content());
		}
		try {
			return objectMapper.writeValueAsString(root);
		} catch (JsonProcessingException e) {
			throw new AiGatewayException("Failed to build OpenAI request.", e);
		}
	}

	private AiGatewayResponse readStreamingResponse(Flow.Publisher<List<ByteBuffer>> body,
		AiProgressListener listener) throws IOException {
		try (PublisherInputStream publisherInput = new PublisherInputStream(body);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(publisherInput,
			java.nio.charset.StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			AiGatewayResponse[] completed = new AiGatewayResponse[1];
			boolean[] draftingNotified = {false};
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					consumeStreamEvent(data, listener, draftingNotified, completed);
					data.setLength(0);
					continue;
				}
				if (line.startsWith("data:")) {
					if (!data.isEmpty()) {
						data.append('\n');
					}
					data.append(line.substring(5).stripLeading());
				}
			}
			consumeStreamEvent(data, listener, draftingNotified, completed);
			if (completed[0] == null) {
				throw new AiGatewayException("OpenAI stream ended before response.completed.");
			}
			return completed[0];
		}
	}

	private void consumeStreamEvent(StringBuilder data, AiProgressListener listener, boolean[] draftingNotified,
		AiGatewayResponse[] completed) {
		if (data.isEmpty() || "[DONE]".contentEquals(data)) {
			return;
		}
		try {
			JsonNode event = objectMapper.readTree(data.toString());
			switch (event.path("type").asText()) {
				case "response.web_search_call.in_progress" -> listener.onProgress(
					AiProgressStage.WEB_SEARCH_STARTED, "웹 검색을 시작했어요.", List.of());
				case "response.web_search_call.searching" -> listener.onProgress(
					AiProgressStage.WEB_SEARCHING, "웹에서 최신 자료와 공식 출처를 검색하고 있어요.", List.of());
				case "response.web_search_call.completed" -> listener.onProgress(
					AiProgressStage.WEB_SEARCH_COMPLETED, "웹 검색을 마치고 확인한 자료를 검토하고 있어요.", List.of());
				case "response.output_text.delta" -> {
					if (!draftingNotified[0]) {
						draftingNotified[0] = true;
						listener.onProgress(AiProgressStage.DRAFTING,
							"답변 문장을 작성하고 있어요.", List.of());
					}
				}
				case "response.completed" -> completed[0] = parseResponse(event.path("response"));
				case "response.failed", "response.incomplete", "error" ->
					throw new AiGatewayException("OpenAI streaming response failed.");
				default -> {
					// 응답 생성에는 다양한 세부 델타 이벤트가 포함될 수 있으며, 필요한 진행 단계만 처리합니다.
				}
			}
		} catch (JsonProcessingException e) {
			throw new AiGatewayException("Failed to parse OpenAI stream event.", e);
		}
	}

	private AiGatewayResponse parseResponse(JsonNode root) {
		try {
			List<String> textParts = new ArrayList<>();
			Map<String, WebSource> sources = new LinkedHashMap<>();
			for (JsonNode output : root.path("output")) {
				if (!"message".equals(output.path("type").asText())) {
					continue;
				}
				for (JsonNode content : output.path("content")) {
					if (!"output_text".equals(content.path("type").asText())) {
						continue;
					}
					FormattedText formatted = citationFormatter.format(content.path("text").asText(""),
						content.path("annotations"));
					if (!formatted.text().isBlank()) {
						textParts.add(formatted.text());
					}
					for (WebSource source : formatted.sources()) {
						sources.putIfAbsent(source.url(), source);
					}
				}
			}
			String text = String.join("\n\n", textParts).trim();
			if (text.isBlank()) {
				throw new AiGatewayException("OpenAI response did not contain assistant text.");
			}
			JsonNode usage = root.path("usage");
			return new AiGatewayResponse(text, root.path("model").asText(model),
				nullableInt(usage, "input_tokens"), nullableInt(usage, "output_tokens"),
				List.copyOf(sources.values()));
		} catch (RuntimeException e) {
			if (e instanceof AiGatewayException aiGatewayException) {
				throw aiGatewayException;
			}
			throw new AiGatewayException("Failed to parse OpenAI response.", e);
		}
	}

	private Integer nullableInt(JsonNode node, String field) {
		return node.has(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : null;
	}

	/** OpenAI publisher를 back-pressure와 즉시 취소가 가능한 InputStream으로 연결합니다. */
	private static final class PublisherInputStream extends InputStream implements Flow.Subscriber<List<ByteBuffer>> {

		private static final byte[] END = new byte[0];
		private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
		private volatile Flow.Subscription subscription;
		private volatile Throwable failure;
		private volatile boolean closed;
		private byte[] current = END;
		private int currentIndex;

		private PublisherInputStream(Flow.Publisher<List<ByteBuffer>> publisher) {
			publisher.subscribe(this);
		}

		@Override
		public void onSubscribe(Flow.Subscription newSubscription) {
			if (subscription != null || closed) {
				newSubscription.cancel();
				return;
			}
			subscription = newSubscription;
			newSubscription.request(1);
		}

		@Override
		public void onNext(List<ByteBuffer> buffers) {
			int length = buffers.stream().mapToInt(ByteBuffer::remaining).sum();
			if (length == 0) {
				requestNext();
				return;
			}
			byte[] chunk = new byte[length];
			int offset = 0;
			for (ByteBuffer buffer : buffers) {
				int remaining = buffer.remaining();
				buffer.get(chunk, offset, remaining);
				offset += remaining;
			}
			chunks.offer(chunk);
		}

		@Override
		public void onError(Throwable throwable) {
			failure = throwable;
			chunks.offer(END);
		}

		@Override
		public void onComplete() {
			chunks.offer(END);
		}

		@Override
		public int read() throws IOException {
			byte[] single = new byte[1];
			return read(single, 0, 1) < 0 ? -1 : single[0] & 0xff;
		}

		@Override
		public int read(byte[] destination, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			if (currentIndex >= current.length && !loadNext()) {
				return -1;
			}
			int copied = Math.min(length, current.length - currentIndex);
			System.arraycopy(current, currentIndex, destination, offset, copied);
			currentIndex += copied;
			return copied;
		}

		private boolean loadNext() throws IOException {
			if (closed) {
				return false;
			}
			try {
				current = chunks.take();
				currentIndex = 0;
			} catch (InterruptedException e) {
				close();
				Thread.currentThread().interrupt();
				InterruptedIOException interrupted = new InterruptedIOException("OpenAI stream reading interrupted.");
				interrupted.initCause(e);
				throw interrupted;
			}
			if (current == END) {
				if (failure != null) {
					throw new IOException("OpenAI response publisher failed.", failure);
				}
				return false;
			}
			requestNext();
			return true;
		}

		private void requestNext() {
			Flow.Subscription currentSubscription = subscription;
			if (!closed && currentSubscription != null) {
				currentSubscription.request(1);
			}
		}

		@Override
		public void close() {
			closed = true;
			Flow.Subscription currentSubscription = subscription;
			if (currentSubscription != null) {
				currentSubscription.cancel();
			}
			chunks.offer(END);
		}
	}
}
