package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import haitai.safemask.domain.fileasset.dto.PdfEditInstruction;
import haitai.safemask.domain.fileasset.dto.PdfEditInstruction.Section;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.junit.jupiter.api.Test;

class PdfEditExecutorTest {
	private final PdfEditExecutor executor = new PdfEditExecutor("");
	@Test void appendsSummaryPageWithoutChangingOriginalPage() throws Exception {
		byte[] edited = executor.apply(blankPdf(1), new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("append_page", "Summary", "SafeMask result", 1, 11.0))));
		try (PDDocument document = Loader.loadPDF(edited)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);
			assertThat(new PDFTextStripper().getText(document)).contains("Summary", "SafeMask result");
		}
	}
	@Test void appendsStructuredSummaryUsingDetectedDocumentStyle() throws Exception {
		byte[] original = styledPdf();
		PdfEditInstruction.Op append = new PdfEditInstruction.Op(
			"append_page", "Executive Summary", null, null, null, "Generated from the document",
			List.of(
				new Section("Key findings", "Sensitive values were safely masked.", List.of()),
				new Section("Next steps", null, List.of("Review the result", "Download the protected copy"))));
		byte[] edited = executor.apply(original, new PdfEditInstruction("result.pdf", List.of(
			append,
			new PdfEditInstruction.Op("add_page_numbers", null, null, null, null))));

		try (PDDocument document = Loader.loadPDF(edited)) {
			assertThat(document.getNumberOfPages()).isEqualTo(2);
			String extracted = new PDFTextStripper().getText(document);
			assertThat(extracted).contains(
				"Executive Summary", "Generated from the document", "Key findings",
				"Sensitive values were safely masked.", "Next steps", "2 / 2");
			TextStyleStripper styles = new TextStyleStripper();
			styles.setStartPage(2);
			styles.setEndPage(2);
			styles.getText(document);
			assertThat(styles.find("Executive Summary").color()).isEqualTo(new Color(31, 59, 102));
			assertThat(styles.find("Key findings").color()).isEqualTo(new Color(31, 59, 102));
		}
	}
	@Test void appendedPageCopiesOriginalPageBoxesAndRotation() throws Exception {
		PDRectangle custom = new PDRectangle(24, 36, 700, 420);
		byte[] original;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage(custom);
			page.setCropBox(new PDRectangle(34, 46, 660, 380));
			page.setRotation(90);
			document.addPage(page); document.save(out); original = out.toByteArray();
		}

		byte[] edited = executor.apply(original, new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("append_page", "Summary", "same size", null, 11.0))));

		try (PDDocument document = Loader.loadPDF(edited)) {
			PDPage first = document.getPage(0);
			PDPage appended = document.getPage(1);
			assertThat(appended.getMediaBox().getLowerLeftX()).isEqualTo(first.getMediaBox().getLowerLeftX());
			assertThat(appended.getMediaBox().getLowerLeftY()).isEqualTo(first.getMediaBox().getLowerLeftY());
			assertThat(appended.getMediaBox().getWidth()).isEqualTo(first.getMediaBox().getWidth());
			assertThat(appended.getMediaBox().getHeight()).isEqualTo(first.getMediaBox().getHeight());
			assertThat(appended.getCropBox().getWidth()).isEqualTo(first.getCropBox().getWidth());
			assertThat(appended.getCropBox().getHeight()).isEqualTo(first.getCropBox().getHeight());
			assertThat(appended.getRotation()).isEqualTo(first.getRotation());
		}
	}
	@Test void longSummaryContinuesOnAdditionalMatchingPages() throws Exception {
		String longText = ("SafeMask summary line ".repeat(15) + "\n").repeat(80) + "FINAL_MARKER";
		byte[] edited = executor.apply(blankPdf(1), new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("append_page", "Summary", longText, null, 11.0))));
		try (PDDocument document = Loader.loadPDF(edited)) {
			assertThat(document.getNumberOfPages()).isGreaterThan(2);
			assertThat(new PDFTextStripper().getText(document)).contains("FINAL_MARKER");
			for (int i = 1; i < document.getNumberOfPages(); i++) {
				assertThat(document.getPage(i).getMediaBox().getWidth()).isEqualTo(document.getPage(0).getMediaBox().getWidth());
			}
		}
	}
	@Test void deletesRequestedPageAndAddsPageNumbers() throws Exception {
		byte[] edited = executor.apply(blankPdf(3), new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("delete_page", null, null, 2, null),
			new PdfEditInstruction.Op("add_page_numbers", null, null, null, 9.0))));
		try (PDDocument document = Loader.loadPDF(edited)) { assertThat(document.getNumberOfPages()).isEqualTo(2); }
	}
	@Test void rejectsKoreanPageWhenNoEmbeddableFontExists() throws Exception {
		PdfEditExecutor withoutFont = new PdfEditExecutor("Z:/missing/font.ttf");
		assertThatThrownBy(() -> withoutFont.apply(blankPdf(1), new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("append_page", "요약", "한글 내용", null, 11.0)))))
			.isInstanceOf(PdfEditExecutor.UnsupportedPdfEditException.class).hasMessageContaining("글꼴");
	}
	@Test void rejectsDigitallySignedPdfBeforeModification() throws Exception {
		byte[] signed;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.addSignature(new PDSignature());
			document.save(out); signed = out.toByteArray();
		}
		assertThatThrownBy(() -> executor.apply(signed, new PdfEditInstruction("result.pdf", List.of(
			new PdfEditInstruction.Op("append_page", "Summary", "text", null, 11.0)))))
			.isInstanceOf(PdfEditExecutor.UnsupportedPdfEditException.class).hasMessageContaining("전자서명");
	}
	private byte[] blankPdf(int pages) throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			for (int i = 0; i < pages; i++) document.addPage(new PDPage()); document.save(out); return out.toByteArray();
		}
	}

	private byte[] styledPdf() throws Exception {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				writeText(content, bold, "SafeMask report", 19, 42, 790, new Color(31, 59, 102));
				writeText(content, regular, "Internal document", 10, 42, 766, new Color(110, 118, 130));
				content.setStrokingColor(new Color(31, 59, 102));
				content.setLineWidth(2);
				content.moveTo(42, 750);
				content.lineTo(553, 750);
				content.stroke();
				writeText(content, regular, "Document body text", 10, 42, 718, new Color(35, 35, 35));
				writeText(content, regular, "1 / 1", 8, 520, 22, new Color(110, 118, 130));
			}
			document.save(out);
			return out.toByteArray();
		}
	}

	private void writeText(PDPageContentStream content, PDType1Font font, String text,
						   float size, float x, float y, Color color) throws Exception {
		content.beginText();
		content.setNonStrokingColor(color);
		content.setFont(font, size);
		content.newLineAtOffset(x, y);
		content.showText(text);
		content.endText();
	}

	private record CapturedText(String text, Color color) {
	}

	private static final class TextStyleStripper extends PDFTextStripper {
		private final List<CapturedText> captured = new ArrayList<>();
		private final Map<String, Color> positionColors = new HashMap<>();
		private Color activeGlyphColor = Color.BLACK;
		private Color streamTextColor = Color.BLACK;
		private final Deque<Color> colorStack = new ArrayDeque<>();

		private TextStyleStripper() throws IOException {
		}

		@Override
		protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
			switch (operator.getName()) {
				case "q" -> colorStack.push(streamTextColor);
				case "Q" -> streamTextColor = colorStack.isEmpty() ? Color.BLACK : colorStack.pop();
				case "g" -> streamTextColor = gray(operands);
				case "rg" -> streamTextColor = rgb(operands);
				case "sc", "scn" -> {
					if (operands.size() == 1) streamTextColor = gray(operands);
					if (operands.size() == 3) streamTextColor = rgb(operands);
				}
				default -> {
				}
			}
			super.processOperator(operator, operands);
		}

		@Override
		protected void showGlyph(Matrix textRenderingMatrix, org.apache.pdfbox.pdmodel.font.PDFont font,
								 int code, Vector displacement) throws IOException {
			activeGlyphColor = streamTextColor;
			super.showGlyph(textRenderingMatrix, font, code, displacement);
		}

		@Override
		protected void processTextPosition(TextPosition text) {
			positionColors.put(positionKey(text), activeGlyphColor);
			super.processTextPosition(text);
		}

		@Override
		protected void writeString(String text, List<TextPosition> positions) throws IOException {
			captured.add(new CapturedText(text,
				positionColors.getOrDefault(positionKey(positions.get(0)), Color.BLACK)));
		}

		private CapturedText find(String expectedText) {
			return captured.stream()
				.filter(value -> value.text().contains(expectedText))
				.findFirst()
				.orElseThrow();
		}

		private String positionKey(TextPosition position) {
			return position.getXDirAdj() + ":" + position.getYDirAdj() + ":" + position.getUnicode();
		}

		private Color gray(List<COSBase> operands) {
			float value = component(operands, 0);
			return new Color(value, value, value);
		}

		private Color rgb(List<COSBase> operands) {
			return new Color(component(operands, 0), component(operands, 1), component(operands, 2));
		}

		private float component(List<COSBase> operands, int index) {
			return Math.max(0, Math.min(1, ((COSNumber) operands.get(index)).floatValue()));
		}
	}
}
