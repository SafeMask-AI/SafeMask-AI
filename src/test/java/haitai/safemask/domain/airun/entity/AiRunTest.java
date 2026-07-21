package haitai.safemask.domain.airun.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import haitai.safemask.domain.airun.enums.AiRunStatus;
import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import org.junit.jupiter.api.Test;

class AiRunTest {

	@Test
	void cancelledRunCannotBeCompletedOrFailed() {
		AiRun run = AiRun.createApproved(mock(ChatRoom.class), mock(ChatMessage.class));
		run.markCalling("gpt-test");

		assertThat(run.markCancelled()).isTrue();
		run.markFailed("late failure");

		assertThat(run.getStatus()).isEqualTo(AiRunStatus.CANCELLED);
		assertThat(run.getErrorMessage()).isNull();
		assertThatThrownBy(() -> run.markCompleted("gpt-test", 1, 1))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void completedRunCannotBeCancelled() {
		AiRun run = AiRun.createApproved(mock(ChatRoom.class), mock(ChatMessage.class));
		run.markCalling("gpt-test");
		run.markCompleted("gpt-test", 1, 1);

		assertThat(run.markCancelled()).isFalse();
		assertThat(run.getStatus()).isEqualTo(AiRunStatus.COMPLETED);
	}

	@Test
	void completedRunCanBeCancelledAfterCompletionOnly() {
		// 완료 직후 사용자 정지: 저장된 답변 폐기와 함께 CANCELLED로 전이할 수 있어야 한다
		AiRun run = AiRun.createApproved(mock(ChatRoom.class), mock(ChatMessage.class));
		run.markCalling("gpt-test");
		run.markCompleted("gpt-test", 1, 1);

		assertThat(run.markCancelledAfterCompletion()).isTrue();
		assertThat(run.getStatus()).isEqualTo(AiRunStatus.CANCELLED);
		// 이미 취소된 실행에 다시 적용하면 거부된다 (중복 정지 요청 멱등 처리)
		assertThat(run.markCancelledAfterCompletion()).isFalse();
	}

	@Test
	void runningRunCannotBeCancelledAfterCompletion() {
		// 아직 완료되지 않은 실행은 일반 취소(markCancelled) 경로만 사용해야 한다
		AiRun run = AiRun.createApproved(mock(ChatRoom.class), mock(ChatMessage.class));
		run.markCalling("gpt-test");

		assertThat(run.markCancelledAfterCompletion()).isFalse();
		assertThat(run.getStatus()).isEqualTo(AiRunStatus.CALLING);
	}
}
