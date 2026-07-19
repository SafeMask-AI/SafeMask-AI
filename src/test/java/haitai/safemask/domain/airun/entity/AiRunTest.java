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
}
