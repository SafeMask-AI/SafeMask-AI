package haitai.safemask.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MaskingTransmissionPreviewUiTest {

	@Test
	void previewShowsServerGeneratedAiTransmissionContentWithoutReconstructingIt() throws IOException {
		String template = resourceText("templates/chat/main.html");
		String script = resourceText("static/js/chat.js");

		assertThat(template)
			.contains("id=\"previewTransmissionTab\"")
			.contains("id=\"previewTransmissionPanel\"")
			.contains("id=\"previewTransmissionText\"")
			.contains("현재 요청에서 이 마스킹본만 외부 AI로 전달됩니다.");
		assertThat(script)
			.contains("const fullText = data.maskedPreview || '';")
			.contains("MAX_TRANSMISSION_DISPLAY_CHARS = 30_000")
			.contains("나머지 ${omittedCharacters.toLocaleString('ko-KR')}자도 실제 전송에는 포함됩니다.")
			.contains("previewTransmissionText.textContent = text")
			.contains("previewTransmissionStale.hidden = !stale")
			.contains("previewCopyButton.disabled = stale");
	}

	@Test
	void textOnlyRequestDoesNotOfferAUnnecessaryDownload() throws IOException {
		String template = resourceText("templates/chat/main.html");
		String script = resourceText("static/js/chat.js");

		assertThat(template).contains("id=\"previewDownloadButton\" hidden");
		assertThat(script).contains("previewDownloadButton.hidden = attachedFiles.length === 0;");
	}

	@Test
	void detectionPanelDoesNotCreateHorizontalOrVerticalScrollbars() throws IOException {
		String style = resourceText("static/css/chat.css").replace("\r\n", "\n");
		String script = resourceText("static/js/chat.js");

		assertThat(style)
			.contains("#previewReviewPanel {\n\tdisplay: block;\n\tmin-width: 0;\n\tmargin: 0;\n\tflex: 0 0 auto;\n\toverflow: visible;")
			.contains(".preview-detail-toggle {", "width: auto;")
			.contains("grid-template-columns: minmax(0, 1fr) 180px auto;");
		assertThat(script).contains("PREVIEW_DETECTION_SAMPLE_LIMIT = 3");
	}

	private String resourceText(String path) throws IOException {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}
}
