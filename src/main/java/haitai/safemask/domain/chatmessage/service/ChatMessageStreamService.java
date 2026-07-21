package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.domain.airun.gateway.AiProgressStage;
import haitai.safemask.domain.airun.gateway.WebSource;
import haitai.safemask.domain.chatmessage.dto.ChatAcceptedResponse;
import haitai.safemask.domain.chatmessage.dto.ChatProgressResponse;
import haitai.safemask.domain.chatmessage.dto.ChatSendRequest;
import haitai.safemask.domain.chatmessage.dto.ChatSendResponse;
import haitai.safemask.domain.chatmessage.dto.ChatStreamErrorResponse;
import haitai.safemask.domain.chatmessage.service.ChatMessageService.PreparedSend;
import haitai.safemask.domain.member.entity.Member;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 실제 AI 실행 단계만 SSE로 알리고, 답변 본문은 원복·파일 처리가 모두 끝난 뒤 한 번에 전달합니다.
 *
 * <p>사용자가 중단 버튼을 누르거나 SSE 연결이 오류·타임아웃으로 종료되면
 * 실행 상태와 외부 호출 스레드를 함께 취소해 사용자가 받지 않을 답변을 계속 생성하지 않습니다.
 */
@Service
@Slf4j
public class ChatMessageStreamService {

	private final ChatMessageService chatMessageService;
	private final ThreadPoolTaskExecutor aiTaskExecutor;
	private final TaskScheduler heartbeatScheduler;
	private final long emitterTimeoutMillis;
	private final Map<Long, Future<?>> activeRuns = new ConcurrentHashMap<>();

	public ChatMessageStreamService(ChatMessageService chatMessageService,
		@Qualifier("aiTaskExecutor") ThreadPoolTaskExecutor aiTaskExecutor,
		@Qualifier("aiHeartbeatScheduler") TaskScheduler heartbeatScheduler,
		@Value("${safemask.ai.sse-timeout:210s}") Duration emitterTimeout) {
		this.chatMessageService = chatMessageService;
		this.aiTaskExecutor = aiTaskExecutor;
		this.heartbeatScheduler = heartbeatScheduler;
		this.emitterTimeoutMillis = emitterTimeout.toMillis();
	}

	public SseEmitter send(Member member, ChatSendRequest request) {
		PreparedSend prepared = chatMessageService.prepare(member, request);
		return start(prepared);
	}

	public SseEmitter sendWithFiles(Member member, ChatSendRequest request, List<MultipartFile> files) {
		// MultipartFile은 HTTP 요청이 끝나면 임시 파일이 정리될 수 있으므로 prepare 단계에서 동기적으로
		// 추출·검증·저장하고, 외부 AI 호출만 전용 executor로 넘깁니다.
		PreparedSend prepared = chatMessageService.prepareWithFiles(member, request, files);
		return start(prepared);
	}

	public boolean cancel(Member member, Long aiRunId) {
		chatMessageService.verifyRunOwner(member, aiRunId);
		if (cancelActiveRun(aiRunId)) {
			return true;
		}
		// 실행이 이미 완료돼 활성 스레드가 없더라도, 사용자가 정지를 눌렀다면 방금 저장된
		// 답변을 받지 않겠다는 뜻이므로 저장분을 폐기한다. (연결 종료 경합으로 이미
		// CANCELLED 처리된 경우도 성공으로 응답해 정지가 조용히 무시되지 않게 한다)
		return chatMessageService.discardCompletedRun(aiRunId);
	}

	private boolean cancelActiveRun(Long aiRunId) {
		Future<?> future = activeRuns.get(aiRunId);
		if (future == null || !chatMessageService.markRunCancelled(aiRunId)) {
			return false;
		}
		activeRuns.remove(aiRunId, future);
		future.cancel(true);
		return true;
	}

	private SseEmitter start(PreparedSend prepared) {
		SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
		StreamSession session = new StreamSession(emitter);
		if (prepared.previewRequired()) {
			session.send("preview", prepared.previewResponse());
			session.complete();
			return emitter;
		}

		AtomicInteger elapsedSeconds = new AtomicInteger();
		ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(
			() -> {
				int elapsed = elapsedSeconds.addAndGet(15);
				session.sendElapsedStatus(elapsed);
				session.send("heartbeat", new ChatProgressResponse("HEARTBEAT", Instant.now().toString()));
			},
			Duration.ofSeconds(15));
		session.onClose(() -> heartbeat.cancel(false));
		session.onDisconnect(() -> {
			if (cancelActiveRun(prepared.aiRunId())) {
				log.info("Cancelled AI run {} because its SSE connection closed", prepared.aiRunId());
			}
		});

		FutureTask<Void> task = new FutureTask<>(() -> {
			execute(prepared, session);
			return null;
		});
		activeRuns.put(prepared.aiRunId(), task);
		session.send("accepted", new ChatAcceptedResponse(prepared.chatRoomId(), prepared.aiRunId()));
		try {
			aiTaskExecutor.execute(task);
		} catch (TaskRejectedException e) {
			activeRuns.remove(prepared.aiRunId(), task);
			task.cancel(false);
			heartbeat.cancel(false);
			chatMessageService.failPrepared(prepared, "AI execution queue is full.");
			session.send("error", new ChatStreamErrorResponse("현재 요청이 많습니다. 잠시 후 다시 시도해 주세요."));
			session.complete();
		}
		return emitter;
	}

	private void execute(PreparedSend prepared, StreamSession session) {
		try {
			ChatSendResponse response = chatMessageService.completePrepared(prepared,
				(stage, message, sources) -> sendProgress(session, stage, message, sources));
			session.send("completed", response);
			session.complete();
		} catch (RuntimeException e) {
			log.error("AI SSE execution failed for run {}", prepared.aiRunId(), e);
			session.send("error", new ChatStreamErrorResponse("AI 응답을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요."));
			session.complete();
		} finally {
			activeRuns.remove(prepared.aiRunId());
		}
	}

	private void sendProgress(StreamSession session, AiProgressStage stage, String message, List<WebSource> sources) {
		session.sendStatus(stage.name(), message);
		if (stage == AiProgressStage.SOURCES_FOUND && sources != null && !sources.isEmpty()) {
			session.send("sources", sources);
		}
	}

	private static final class StreamSession {

		private final SseEmitter emitter;
		private final AtomicBoolean closed = new AtomicBoolean(false);
		private volatile String lastStatusStage = "WAITING";
		private volatile String lastStatusMessage = "답변을 생성하고 있어요.";
		private volatile Runnable closeAction = () -> {
		};
		private volatile Runnable disconnectAction = () -> {
		};

		private StreamSession(SseEmitter emitter) {
			this.emitter = emitter;
			// session.complete()은 먼저 closed를 확정하므로 이 콜백이 취소를 재실행하지 않는다.
			// 반대로 컨테이너가 외부 연결 종료를 completion으로만 알리는 경우에는 disconnect로 처리한다.
			emitter.onCompletion(this::disconnect);
			emitter.onTimeout(this::disconnect);
			emitter.onError(error -> disconnect());
		}

		private synchronized void send(String eventName, Object data) {
			if (closed.get()) {
				return;
			}
			try {
				emitter.send(SseEmitter.event().name(eventName).data(data));
			} catch (IOException | IllegalStateException e) {
				disconnect();
			}
		}

		private synchronized void sendStatus(String stage, String message) {
			if (stage != null && !stage.isBlank()) {
				lastStatusStage = stage;
			}
			if (message != null && !message.isBlank()) {
				lastStatusMessage = message;
			}
			send("status", new ChatProgressResponse(lastStatusStage, lastStatusMessage));
		}

		private synchronized void sendElapsedStatus(int elapsedSeconds) {
			send("status", new ChatProgressResponse(lastStatusStage,
				lastStatusMessage + " · " + elapsedSeconds + "초 경과"));
		}

		private void onClose(Runnable action) {
			this.closeAction = action == null ? () -> {
			} : action;
			if (closed.get()) {
				this.closeAction.run();
			}
		}

		private void onDisconnect(Runnable action) {
			this.disconnectAction = action == null ? () -> {
			} : action;
			if (closed.get()) {
				this.disconnectAction.run();
			}
		}

		private void complete() {
			if (closed.compareAndSet(false, true)) {
				runQuietly(closeAction);
				emitter.complete();
			}
		}

		private void disconnect() {
			if (closed.compareAndSet(false, true)) {
				runQuietly(closeAction);
				runQuietly(disconnectAction);
			}
		}

		/**
		 * 세션 정리 작업의 예외가 호출자(진행 상태 전송·답변 생성 스레드)로 전파되지 않게 합니다.
		 * 예: 취소 상태 전이가 DB 제약 등으로 실패해도 답변 파이프라인 전체를 오류로 만들지 않습니다.
		 */
		private static void runQuietly(Runnable action) {
			try {
				action.run();
			} catch (RuntimeException e) {
				log.warn("SSE 세션 정리 작업이 실패했습니다", e);
			}
		}
	}
}
