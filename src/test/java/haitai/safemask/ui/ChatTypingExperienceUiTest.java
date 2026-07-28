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
	void typingRevealsStructureBlocksOnlyWhenTheirTextStarts() throws Exception {
		String script = Files.readString(Path.of("src/main/resources/static/js/chat.js"), StandardCharsets.UTF_8);
		String chatCss = Files.readString(Path.of("src/main/resources/static/css/chat.css"), StandardCharsets.UTF_8);

		// 표·목록 골격이 글자보다 먼저 그려지지 않도록, 블록을 감췄다가 첫 글자에서 드러낸다.
		assertThat(script)
			.contains("const REVEAL_BLOCK_TAGS", "element.classList.add('reveal-pending')",
				"if (item.visible === 0 && revealCount > 0)", "element.classList.remove('reveal-pending')");
		// 셀을 감추면 같은 행의 열 정렬이 무너지므로 td/th는 대상에서 제외한다.
		assertThat(script.substring(script.indexOf("const REVEAL_BLOCK_TAGS"),
			script.indexOf("const REVEAL_BLOCK_TAGS") + 300))
			.doesNotContain("'TD'", "'TH'");
		// 연출이 끝나 .typing이 사라지면 남은 블록이 자동으로 모두 보여야 한다.
		assertThat(chatCss).contains(".message-bubble.typing .reveal-pending");
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
