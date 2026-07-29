package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.domain.fileasset.dto.WordEditInstruction;
import haitai.safemask.domain.fileasset.dto.WordEditInstruction.Op;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WordEditExecutorTest {

	private final WordEditExecutor executor = new WordEditExecutor();

	@Test
	@DisplayName("여러 run에 걸친 문구를 치환하고 기존 run 서식을 보존한다")
	void replaceAcrossRunsPreservesFormatting() throws IOException {
		byte[] original;
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XWPFParagraph paragraph = document.createParagraph();
			XWPFRun first = paragraph.createRun();
			first.setBold(true);
			first.setText("담당자 김");
			XWPFRun second = paragraph.createRun();
			second.setColor("C00000");
			second.setText("민수 연락처");
			document.write(out);
			original = out.toByteArray();
		}

		byte[] edited = executor.apply(original, instruction(
			new Op("replace_text", "김민수", "이서연", null, null)));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(edited))) {
			XWPFParagraph paragraph = document.getParagraphArray(0);
			assertThat(paragraph.getText()).isEqualTo("담당자 이서연 연락처");
			assertThat(paragraph.getRuns().get(0).isBold()).isTrue();
			assertThat(paragraph.getRuns().get(1).getColor()).isEqualTo("C00000");
		}
	}

	@Test
	@DisplayName("문단 추가는 마지막 본문 문단의 서식을 상속한다")
	void appendParagraphInheritsNearestBodyFormatting() throws IOException {
		byte[] original;
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XWPFParagraph body = document.createParagraph();
			body.setAlignment(ParagraphAlignment.BOTH);
			body.setSpacingAfter(240);
			XWPFRun run = body.createRun();
			run.setFontFamily("맑은 고딕");
			run.setFontSize(12);
			run.setColor("244062");
			run.setText("유지 문단");
			document.createParagraph().createRun().setText("삭제 대상 문단");
			document.write(out);
			original = out.toByteArray();
		}
		byte[] edited = executor.apply(original, instruction(
			new Op("delete_paragraph", null, null, "삭제 대상", null),
			new Op("append_paragraph", null, null, null, "추가 문단", "body")));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(edited))) {
			assertThat(document.getParagraphs()).extracting(XWPFParagraph::getText)
				.containsExactly("유지 문단", "추가 문단");
			XWPFParagraph appended = document.getParagraphArray(1);
			assertThat(appended.getAlignment()).isEqualTo(ParagraphAlignment.BOTH);
			assertThat(appended.getSpacingAfter()).isEqualTo(240);
			assertThat(appended.getRuns().get(0).getFontFamily()).isEqualTo("맑은 고딕");
			assertThat(appended.getRuns().get(0).getFontSizeAsDouble()).isEqualTo(12);
			assertThat(appended.getRuns().get(0).getColor()).isEqualTo("244062");
		}
	}

	@Test
	@DisplayName("title 역할은 마지막 문단이 아니라 기존 제목 서식을 선택한다")
	void appendTitleUsesExistingTitleFormatting() throws IOException {
		byte[] original;
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			XWPFParagraph title = document.createParagraph();
			title.setStyle("Title");
			XWPFRun titleRun = title.createRun();
			titleRun.setBold(true);
			titleRun.setFontSize(20);
			titleRun.setColor("17365D");
			titleRun.setText("기존 제목");
			document.createParagraph().createRun().setText("기존 본문");
			document.write(out);
			original = out.toByteArray();
		}

		byte[] edited = executor.apply(original, instruction(
			new Op("append_paragraph", null, null, null, "새 요약", "title")));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(edited))) {
			XWPFRun appended = document.getParagraphArray(2).getRuns().get(0);
			assertThat(appended.isBold()).isTrue();
			assertThat(appended.getFontSizeAsDouble()).isEqualTo(20);
			assertThat(appended.getColor()).isEqualTo("17365D");
		}
	}

	@Test
	@DisplayName("문단 삭제 뒤 이어지는 치환은 분리된 XML 문단을 건드리지 않는다")
	void replaceAfterDeleteUsesOnlyAttachedParagraphs() throws IOException {
		byte[] edited = executor.apply(documentWithParagraphs("삭제할 김민수", "유지할 김민수"), instruction(
			new Op("delete_paragraph", null, null, "삭제할", null),
			new Op("replace_text", "김민수", "이서연", null, null)));

		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(edited))) {
			assertThat(document.getParagraphs()).extracting(XWPFParagraph::getText)
				.containsExactly("유지할 이서연");
		}
	}

	@Test
	@DisplayName("존재하지 않는 문구 치환은 성공으로 위장하지 않고 거절한다")
	void rejectMissingText() throws IOException {
		assertThatThrownBy(() -> executor.apply(documentWithParagraphs("본문"), instruction(
			new Op("replace_text", "없는 문구", "새 문구", null, null))))
			.isInstanceOf(WordEditExecutor.UnsupportedWordEditException.class)
			.hasMessageContaining("찾지 못했습니다");
	}

	private WordEditInstruction instruction(Op... ops) {
		return new WordEditInstruction("결과.docx", List.of(ops));
	}

	private byte[] documentWithParagraphs(String... paragraphs) throws IOException {
		try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (String text : paragraphs) {
				document.createParagraph().createRun().setText(text);
			}
			document.write(out);
			return out.toByteArray();
		}
	}
}
