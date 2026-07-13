package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.fileasset.dto.PdfEditInstruction;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 원본 PDF를 건드리지 않고 복사본에 안전한 페이지 단위 연산을 적용합니다. */
@Component
public class PdfEditExecutor {
	private static final int MAX_PAGES = 300;
	private static final int MAX_OPS = 20;
	private static final int MAX_APPEND_CHARS = 30_000;
	private final String configuredFontPath;
	public PdfEditExecutor(@Value("${safemask.pdf.font-path:}") String configuredFontPath) {
		this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
	}
	public static class UnsupportedPdfEditException extends RuntimeException {
		public UnsupportedPdfEditException(String message) { super(message); }
	}
	public byte[] apply(byte[] original, PdfEditInstruction instruction) {
		try (PDDocument document = Loader.loadPDF(original); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			if (!document.getCurrentAccessPermission().canModify()) throw new UnsupportedPdfEditException("수정 권한이 없는 PDF입니다");
			if (instruction == null || instruction.ops() == null || instruction.ops().isEmpty()) throw new UnsupportedPdfEditException("적용할 PDF 편집 내용이 없습니다");
			if (!document.getSignatureDictionaries().isEmpty()) throw new UnsupportedPdfEditException("전자서명된 PDF는 수정하면 서명이 무효화되므로 편집할 수 없습니다");
			if (document.getNumberOfPages() > MAX_PAGES) throw new UnsupportedPdfEditException("PDF 페이지가 너무 많아 편집할 수 없습니다");
			if (instruction.ops().size() > MAX_OPS) throw new UnsupportedPdfEditException("PDF 편집 작업은 한 번에 최대 " + MAX_OPS + "개까지 가능합니다");
			for (PdfEditInstruction.Op op : instruction.ops()) apply(document, op);
			document.save(out); return out.toByteArray();
		} catch (IOException e) { throw new CustomException(ErrorCode.INVALID_REQUEST, e); }
	}
	private void apply(PDDocument document, PdfEditInstruction.Op op) throws IOException {
		if (op == null || op.op() == null) throw new UnsupportedPdfEditException("알 수 없는 PDF 편집 지시가 있습니다");
		switch (op.op()) {
			case "append_page" -> appendPage(document, op);
			case "delete_page" -> deletePage(document, op.page());
			case "add_page_numbers" -> addPageNumbers(document, op.fontSize());
			default -> throw new UnsupportedPdfEditException("지원하지 않는 PDF 편집 지시입니다: " + op.op());
		}
	}
	private void appendPage(PDDocument document, PdfEditInstruction.Op op) throws IOException {
		String text = op.text() == null ? "" : op.text().trim();
		if (text.isEmpty()) throw new UnsupportedPdfEditException("추가할 PDF 페이지 내용이 없습니다");
		if (text.length() > MAX_APPEND_CHARS) throw new UnsupportedPdfEditException("추가할 PDF 내용이 너무 깁니다");
		// PDF는 A4만 사용하는 형식이 아닙니다. Letter·가로형·사용자 정의 크기 문서에
		// A4 페이지를 붙이면 결과 파일 안에서 페이지 크기가 튀므로 첫 페이지의 실제
		// MediaBox/CropBox/회전값을 복제해 새 페이지를 만듭니다.
		PDPage reference = document.getPage(0);
		PDFont font = resolveFont(document, (op.title() == null ? "" : op.title()) + text);
		float size = op.fontSize() == null ? 11f : Math.max(8f, Math.min(18f, op.fontSize().floatValue()));
		PDRectangle box = reference.getCropBox();
		float margin = Math.min(55, Math.max(30, box.getWidth() * 0.08f));
		float width = Math.max(120, box.getWidth() - margin * 2);
		List<TextLine> lines = new ArrayList<>();
		if (op.title() != null && !op.title().isBlank()) lines.addAll(wrap(font, op.title(), 16, width));
		for (String paragraph : text.split("\\R", -1)) {
			lines.add(new TextLine("", 6));
			lines.addAll(wrap(font, paragraph, size, width));
		}
		PDPage page = null;
		PDPageContentStream content = null;
		float y = 0;
		try {
			for (TextLine line : lines) {
				if (page == null || y - line.height() < box.getLowerLeftY() + margin) {
					if (content != null) content.close();
					page = createMatchingPage(document, reference);
					content = new PDPageContentStream(document, page);
					y = page.getCropBox().getUpperRightY() - margin;
				}
				if (!line.text().isEmpty()) writeLine(content, font, line.text(), line.size(), page.getCropBox().getLowerLeftX() + margin, y);
				y -= line.height();
			}
		} finally {
			if (content != null) content.close();
		}
	}

	private record TextLine(String text, float size) { float height() { return size + 5; } }
	private List<TextLine> wrap(PDFont font, String text, float size, float width) throws IOException {
		List<TextLine> lines = new ArrayList<>(); StringBuilder line = new StringBuilder();
		for (int offset = 0; offset < text.length();) { int cp = text.codePointAt(offset); String next = new String(Character.toChars(cp));
			if (!line.isEmpty() && font.getStringWidth(line + next) / 1000f * size > width) { lines.add(new TextLine(line.toString(), size)); line.setLength(0); }
			line.append(next); offset += Character.charCount(cp); }
		if (!line.isEmpty()) lines.add(new TextLine(line.toString(), size));
		return lines;
	}
	private PDPage createMatchingPage(PDDocument document, PDPage reference) {
		PDPage page = new PDPage(copyRectangle(reference.getMediaBox()));
		page.setCropBox(copyRectangle(reference.getCropBox())); page.setRotation(reference.getRotation()); document.addPage(page); return page;
	}

	private PDRectangle copyRectangle(PDRectangle source) {
		return new PDRectangle(source.getLowerLeftX(), source.getLowerLeftY(), source.getWidth(), source.getHeight());
	}
	private void writeLine(PDPageContentStream content, PDFont font, String text, float size, float x, float y) throws IOException {
		content.beginText(); content.setFont(font, size); content.newLineAtOffset(x, y); content.showText(text); content.endText();
	}
	private void deletePage(PDDocument document, Integer page) {
		if (page == null || page < 1 || page > document.getNumberOfPages()) throw new UnsupportedPdfEditException("삭제할 PDF 페이지 번호가 올바르지 않습니다");
		if (document.getNumberOfPages() == 1) throw new UnsupportedPdfEditException("PDF의 유일한 페이지는 삭제할 수 없습니다");
		document.removePage(page - 1);
	}
	private void addPageNumbers(PDDocument document, Double requestedSize) throws IOException {
		float size = requestedSize == null ? 9f : requestedSize.floatValue(); PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		for (int i = 0; i < document.getNumberOfPages(); i++) { PDPage page = document.getPage(i); String value = (i + 1) + " / " + document.getNumberOfPages();
			try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) { writeLine(content, font, value, size, page.getMediaBox().getWidth() / 2 - 15, 22); }
		}
	}
	private PDFont resolveFont(PDDocument document, String text) throws IOException {
		boolean requiresUnicode = text.codePoints().anyMatch(cp -> cp > 255);
		if (!requiresUnicode) return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		List<Path> candidates = configuredFontPath.isBlank() ? List.of(Path.of("C:/Windows/Fonts/malgun.ttf"), Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc")) : List.of(Path.of(configuredFontPath));
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) {
				try (InputStream input = Files.newInputStream(candidate)) {
					return PDType0Font.load(document, input, true);
				}
			}
		}
		throw new UnsupportedPdfEditException("한글 PDF 생성을 위한 글꼴이 없습니다. safemask.pdf.font-path를 설정해 주세요");
	}
}
