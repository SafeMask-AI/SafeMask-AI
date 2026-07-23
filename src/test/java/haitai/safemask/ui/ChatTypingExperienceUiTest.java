package haitai.safemask.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChatTypingExperienceUiTest {

	@Test
	void typingRevealsTextInsideCompletedMarkdownDom() throws Exception {
		String script = Files.readString(Path.of("src/main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

		assertThat(script)
			.contains("const baseDelay = 27", "bubble.innerHTML = renderMarkdown(text)",
				"document.createTreeWalker(bubble, NodeFilter.SHOW_TEXT)",
				"item.node.nodeValue = item.text.slice(0, item.visible)",
				"scrollMessageListToBottom();")
			.doesNotContain("renderMarkdown(visibleText", "if (/[.!?。！？\\n]$/.test");
	}

	@Test
	void sentQuestionUsesCancelableReducedMotionAwareAnchorAnimation() throws Exception {
		String script = Files.readString(Path.of("src/main/resources/static/js/chat.js"), StandardCharsets.UTF_8);

		assertThat(script)
			.contains("animateTurnAnchor(targetScrollTop)", "const duration = 260",
				"1 - Math.pow(1 - progress, 3)", "window.requestAnimationFrame(step)",
				"(prefers-reduced-motion: reduce)", "cancelTurnAnchorAnimation");
	}

	@Test
	void typingDoesNotRenderAnOrphanCaretAfterTheLastMarkdownBlock() throws Exception {
		String chatCss = Files.readString(Path.of("src/main/resources/static/css/chat.css"), StandardCharsets.UTF_8);

		assertThat(chatCss)
			.doesNotContain(".message-bubble.markdown.typing > :last-child::after", "typing-caret-blink");
	}

	@Test
	void logoutDescriptionUsesBalancedNonOrphanWrapping() throws Exception {
		String script = Files.readString(Path.of("src/main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
		String chatCss = Files.readString(Path.of("src/main/resources/static/css/chat.css"), StandardCharsets.UTF_8);
		String adminCss = Files.readString(Path.of("src/main/resources/static/css/admin.css"), StandardCharsets.UTF_8);

		assertThat(script).contains("현재 기기의 로그인 정보를 안전하게 삭제합니다.");
		assertThat(chatCss).contains("word-break: keep-all", "text-wrap: balance");
		assertThat(adminCss).contains("word-break: keep-all", "text-wrap: balance");
	}
}
