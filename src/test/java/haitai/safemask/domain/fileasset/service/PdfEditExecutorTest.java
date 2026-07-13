package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import haitai.safemask.domain.fileasset.dto.PdfEditInstruction;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.text.PDFTextStripper;
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
}
