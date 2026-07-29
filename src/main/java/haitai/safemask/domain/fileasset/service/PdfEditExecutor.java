package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.fileasset.dto.PdfEditInstruction;
import haitai.safemask.domain.fileasset.dto.PdfEditInstruction.Section;
import haitai.safemask.domain.fileasset.service.PdfStyleAnalyzer.PageNumberMark;
import haitai.safemask.domain.fileasset.service.PdfStyleAnalyzer.PdfStyleProfile;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

/**
 * 원본 PDF를 건드리지 않고 복사본에 안전한 페이지 단위 연산을 적용합니다.
 *
 * <p>PDF는 Word처럼 제목·본문 스타일이 명시된 문서가 아니므로 서버가 텍스트 위치,
 * 크기, 색상과 페이지 번호를 분석해 보수적인 디자인 프로필을 만듭니다. 원본 페이지를
 * 통째로 배경으로 복제하면 민감한 본문까지 새 페이지에 중복될 수 있어 사용하지 않습니다.
 * 새 페이지에는 분석된 타이포그래피와 여백만 적용하고, 반복 장식의 안전한 분리가
 * 불가능한 경우에는 장식을 임의로 복사하지 않습니다.
 */
@Component
public class PdfEditExecutor {
	private static final int MAX_PAGES = 300;
	private static final int MAX_OPS = 20;
	private static final int MAX_APPEND_CHARS = 30_000;
	private static final int MAX_TOTAL_APPEND_CHARS = 60_000;

	private final String configuredFontPath;
	private final PdfStyleAnalyzer styleAnalyzer = new PdfStyleAnalyzer();

	public PdfEditExecutor(@Value("${safemask.pdf.font-path:}") String configuredFontPath) {
		this.configuredFontPath = configuredFontPath == null ? "" : configuredFontPath.trim();
	}

	public static class UnsupportedPdfEditException extends RuntimeException {
		public UnsupportedPdfEditException(String message) {
			super(message);
		}
	}

	public byte[] apply(byte[] original, PdfEditInstruction instruction) {
		try (PDDocument document = Loader.loadPDF(original);
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			validateDocument(document, instruction);
			PdfStyleProfile appendStyle = null;
			for (PdfEditInstruction.Op op : instruction.ops()) {
				if (op == null || op.op() == null) {
					throw new UnsupportedPdfEditException("알 수 없는 PDF 편집 지시가 있습니다");
				}
				switch (op.op()) {
					case "append_page" -> {
						if (appendStyle == null) {
							// 여러 요약 섹션을 연속 추가해도 원본 전체를 매번 다시 읽지 않습니다.
							appendStyle = styleAnalyzer.analyze(document);
						}
						appendPage(document, op, appendStyle);
					}
					case "delete_page" -> deletePage(document, op.page());
					case "add_page_numbers" -> addPageNumbers(document, op.fontSize());
					default -> throw new UnsupportedPdfEditException(
						"지원하지 않는 PDF 편집 지시입니다: " + op.op());
				}
			}
			document.save(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private void validateDocument(PDDocument document, PdfEditInstruction instruction) {
		if (!document.getCurrentAccessPermission().canModify()) {
			throw new UnsupportedPdfEditException("수정 권한이 없는 PDF입니다");
		}
		if (instruction == null || instruction.ops() == null || instruction.ops().isEmpty()) {
			throw new UnsupportedPdfEditException("적용할 PDF 편집 내용이 없습니다");
		}
		if (!document.getSignatureDictionaries().isEmpty()) {
			throw new UnsupportedPdfEditException("전자서명된 PDF는 수정하면 서명이 무효화되므로 편집할 수 없습니다");
		}
		if (document.getNumberOfPages() == 0) {
			throw new UnsupportedPdfEditException("페이지가 없는 PDF는 편집할 수 없습니다");
		}
		if (document.getNumberOfPages() > MAX_PAGES) {
			throw new UnsupportedPdfEditException("PDF 페이지가 너무 많아 편집할 수 없습니다");
		}
		if (instruction.ops().size() > MAX_OPS) {
			throw new UnsupportedPdfEditException("PDF 편집 작업은 한 번에 최대 " + MAX_OPS + "개까지 가능합니다");
		}
		int totalAppendChars = 0;
		for (PdfEditInstruction.Op op : instruction.ops()) {
			if (op != null && "append_page".equals(op.op())) {
				totalAppendChars += appendCharacterCount(op, normalizedSections(op.sections()));
			}
		}
		if (totalAppendChars > MAX_TOTAL_APPEND_CHARS) {
			throw new UnsupportedPdfEditException("한 번에 추가할 PDF 전체 내용이 너무 깁니다");
		}
	}

	private void appendPage(PDDocument document, PdfEditInstruction.Op op, PdfStyleProfile style) throws IOException {
		String title = op.title() == null ? "" : op.title().trim();
		List<Section> sections = normalizedSections(op.sections());
		String text = op.text() == null ? "" : op.text().trim();
		if (title.isEmpty() && text.isEmpty() && sections.isEmpty()) {
			throw new UnsupportedPdfEditException("추가할 PDF 페이지 내용이 없습니다");
		}
		if (appendCharacterCount(op, sections) > MAX_APPEND_CHARS) {
			throw new UnsupportedPdfEditException("추가할 PDF 내용이 너무 깁니다");
		}

		String fontText = title + safe(op.subtitle()) + text + sectionText(sections);
		FontSet fonts = resolveFonts(document, fontText);
		float bodySize = op.fontSize() == null
			? style.bodySize()
			: clamp(op.fontSize().floatValue(), 8, 18);
		try (PdfPageWriter writer = new PdfPageWriter(
			document, style, fonts, title, safe(op.subtitle()), bodySize)) {
			if (!sections.isEmpty()) {
				for (Section section : sections) {
					writer.writeSection(section);
				}
			} else {
				writer.writePlainText(text);
			}
		}
	}

	private List<Section> normalizedSections(List<Section> sections) {
		if (sections == null) {
			return List.of();
		}
		return sections.stream()
			.filter(section -> section != null
				&& (!safe(section.heading()).isBlank()
				|| !safe(section.text()).isBlank()
				|| (section.items() != null && section.items().stream().anyMatch(item -> !safe(item).isBlank()))))
			.toList();
	}

	private int appendCharacterCount(PdfEditInstruction.Op op, List<Section> sections) {
		return safe(op.title()).length() + safe(op.subtitle()).length() + safe(op.text()).length()
			+ sectionText(sections).length();
	}

	private String sectionText(List<Section> sections) {
		StringBuilder text = new StringBuilder();
		for (Section section : sections) {
			text.append(safe(section.heading())).append(safe(section.text()));
			if (section.items() != null) {
				section.items().forEach(item -> text.append(safe(item)));
			}
		}
		return text.toString();
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}

	private void deletePage(PDDocument document, Integer page) {
		if (page == null || page < 1 || page > document.getNumberOfPages()) {
			throw new UnsupportedPdfEditException("삭제할 PDF 페이지 번호가 올바르지 않습니다");
		}
		if (document.getNumberOfPages() == 1) {
			throw new UnsupportedPdfEditException("PDF의 유일한 페이지는 삭제할 수 없습니다");
		}
		document.removePage(page - 1);
	}

	/**
	 * 기존 페이지 번호가 있으면 같은 위치·크기·색상을 사용하고 작은 흰색 영역으로 기존 숫자만
	 * 덮은 뒤 전체 페이지 수를 다시 기록합니다. 기존 번호가 없으면 분석된 여백과 muted 색상을
	 * 사용합니다. 회전 페이지는 좌표 변환 오판으로 본문을 가릴 수 있어 명시적으로 거절합니다.
	 */
	private void addPageNumbers(PDDocument document, Double requestedSize) throws IOException {
		for (PDPage page : document.getPages()) {
			if (page.getRotation() != 0) {
				throw new UnsupportedPdfEditException("회전된 페이지가 있는 PDF에는 페이지 번호를 안전하게 배치할 수 없습니다");
			}
		}
		PdfStyleProfile style = styleAnalyzer.analyze(document);
		FontSet fonts = resolveFonts(document, "0123456789 /");
		Map<Integer, PageNumberMark> marksByPage = new HashMap<>();
		for (PageNumberMark mark : style.pageNumbers()) {
			marksByPage.put(mark.pageIndex(), mark);
		}
		PageNumberMark representative = style.pageNumbers().stream().findFirst().orElse(null);
		float size = requestedSize == null
			? representative == null ? style.pageNumberSize() : representative.fontSize()
			: clamp(requestedSize.floatValue(), 7, 14);

		for (int i = 0; i < document.getNumberOfPages(); i++) {
			PDPage page = document.getPage(i);
			PDRectangle crop = page.getCropBox();
			String value = (i + 1) + " / " + document.getNumberOfPages();
			float textWidth = textWidth(fonts.regular(), value, size);
			PageNumberMark existing = marksByPage.get(i);
			float x;
			float y;
			Color color;

			try (PDPageContentStream content = new PDPageContentStream(
				document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
				if (existing != null) {
					float coverX = existing.x() - 2;
					float coverY = crop.getUpperRightY() - existing.topY() - existing.height() - 2;
					content.setNonStrokingColor(Color.WHITE);
					content.addRect(coverX, coverY, existing.width() + 4, existing.height() + 5);
					content.fill();
					x = existing.x() + existing.width() - textWidth;
					y = crop.getUpperRightY() - existing.topY();
					color = existing.color();
				} else if (representative != null) {
					float rightInset = representative.pageWidth()
						- (representative.x() + representative.width());
					x = crop.getUpperRightX() - rightInset - textWidth;
					y = crop.getLowerLeftY() + representative.baselineFromBottom();
					color = representative.color();
				} else {
					x = crop.getUpperRightX() - style.rightMargin() - textWidth;
					y = crop.getLowerLeftY() + Math.max(18, style.bottomMargin() * 0.45f);
					color = style.mutedColor();
				}
				writeLine(content, fonts.regular(), value, size, x, y, color);
			}
		}
	}

	private FontSet resolveFonts(PDDocument document, String text) throws IOException {
		boolean requiresUnicode = text.codePoints().anyMatch(cp -> cp > 255);
		Optional<Path> regularPath = resolveRegularFontPath();
		if (regularPath.isPresent()) {
			PDFont regular = loadType0Font(document, regularPath.get());
			Path boldPath = resolveBoldFontPath(regularPath.get()).orElse(regularPath.get());
			PDFont bold = loadType0Font(document, boldPath);
			return new FontSet(regular, bold);
		}
		if (!requiresUnicode) {
			return new FontSet(
				new PDType1Font(Standard14Fonts.FontName.HELVETICA),
				new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
		}
		throw new UnsupportedPdfEditException(
			"한글 PDF 생성을 위한 글꼴이 없습니다. safemask.pdf.font-path를 설정해 주세요");
	}

	private Optional<Path> resolveRegularFontPath() {
		List<Path> candidates = configuredFontPath.isBlank()
			? List.of(
				Path.of("C:/Windows/Fonts/malgun.ttf"),
				Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
				Path.of("/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc"))
			: List.of(Path.of(configuredFontPath));
		return candidates.stream().filter(Files::isRegularFile).findFirst();
	}

	private Optional<Path> resolveBoldFontPath(Path regular) {
		String fileName = regular.getFileName().toString();
		List<Path> candidates = new ArrayList<>();
		if ("malgun.ttf".equalsIgnoreCase(fileName)) {
			candidates.add(regular.resolveSibling("malgunbd.ttf"));
		}
		candidates.add(regular.resolveSibling(fileName
			.replace("Regular", "Bold")
			.replace("regular", "bold")));
		return candidates.stream().filter(Files::isRegularFile).findFirst();
	}

	private PDFont loadType0Font(PDDocument document, Path path) throws IOException {
		try (InputStream input = Files.newInputStream(path)) {
			return PDType0Font.load(document, input, true);
		}
	}

	private PDRectangle copyRectangle(PDRectangle source) {
		return new PDRectangle(source.getLowerLeftX(), source.getLowerLeftY(),
			source.getWidth(), source.getHeight());
	}

	private float textWidth(PDFont font, String text, float size) throws IOException {
		return font.getStringWidth(text) / 1000f * size;
	}

	private void writeLine(PDPageContentStream content, PDFont font, String text,
						   float size, float x, float y, Color color) throws IOException {
		content.beginText();
		content.setNonStrokingColor(color);
		content.setFont(font, size);
		content.newLineAtOffset(x, y);
		content.showText(text);
		content.endText();
	}

	private float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private record FontSet(PDFont regular, PDFont bold) {
	}

	private final class PdfPageWriter implements AutoCloseable {
		private final PDDocument document;
		private final PdfStyleProfile style;
		private final FontSet fonts;
		private final String title;
		private final String subtitle;
		private final float bodySize;
		private PDPage page;
		private PDPageContentStream content;
		private float y;
		private int appendedPageCount;

		private PdfPageWriter(PDDocument document, PdfStyleProfile style, FontSet fonts,
							  String title, String subtitle, float bodySize) throws IOException {
			this.document = document;
			this.style = style;
			this.fonts = fonts;
			this.title = title;
			this.subtitle = subtitle;
			this.bodySize = bodySize;
			startPage();
		}

		private void startPage() throws IOException {
			if (content != null) {
				content.close();
			}
			if (document.getNumberOfPages() >= MAX_PAGES) {
				throw new UnsupportedPdfEditException(
					"편집 결과 PDF가 최대 " + MAX_PAGES + "페이지를 초과합니다");
			}
			page = new PDPage(copyRectangle(style.mediaBox()));
			page.setCropBox(copyRectangle(style.cropBox()));
			page.setRotation(style.rotation());
			document.addPage(page);
			content = new PDPageContentStream(document, page);
			appendedPageCount++;

			PDRectangle crop = page.getCropBox();
			float x = crop.getLowerLeftX() + style.leftMargin();
			y = crop.getUpperRightY() - style.topMargin();
			String pageTitle = appendedPageCount == 1 ? title
				: title.isBlank() ? "Continued" : title + " (cont.)";
			if (!pageTitle.isBlank()) {
				writeLine(content, fonts.bold(), pageTitle, style.titleSize(), x, y, style.primaryColor());
				y -= style.titleSize() + 7;
			}
			if (appendedPageCount == 1 && !subtitle.isBlank()) {
				writeLine(content, fonts.regular(), subtitle,
					Math.max(8, bodySize - 1), x, y, style.mutedColor());
				y -= bodySize + 8;
			}
			content.setStrokingColor(style.primaryColor());
			content.setLineWidth(Math.max(1.2f, style.titleSize() * 0.12f));
			content.moveTo(x, y);
			content.lineTo(crop.getUpperRightX() - style.rightMargin(), y);
			content.stroke();
			y -= Math.max(18, bodySize * 1.8f);
		}

		private void writeSection(Section section) throws IOException {
			String heading = safe(section.heading()).trim();
			List<String> bodyLines = new ArrayList<>();
			for (String paragraph : safe(section.text()).strip().split("\\R", -1)) {
				if (!paragraph.isBlank()) {
					bodyLines.addAll(wrap(fonts.regular(), paragraph, bodySize, contentWidth()));
				}
			}
			if (section.items() != null) {
				for (String item : section.items()) {
					if (!safe(item).isBlank()) {
						bodyLines.addAll(wrap(fonts.regular(), "• " + item.trim(), bodySize,
							contentWidth() - bodySize));
					}
				}
			}

			float headingHeight = heading.isBlank() ? 0 : Math.max(20, style.sectionSize() + 9);
			float required = headingHeight + Math.min(2, bodyLines.size()) * lineHeight(bodySize) + 10;
			ensureSpace(required);
			if (!heading.isBlank()) {
				PDRectangle crop = page.getCropBox();
				float x = crop.getLowerLeftX() + style.leftMargin();
				content.setNonStrokingColor(style.sectionFill());
				content.addRect(x, y - headingHeight + 4, contentWidth(), headingHeight);
				content.fill();
				writeLine(content, fonts.bold(), heading, style.sectionSize(),
					x + 8, y - style.sectionSize() + 1, style.primaryColor());
				y -= headingHeight + 7;
			}
			for (String line : bodyLines) {
				ensureSpace(lineHeight(bodySize));
				writeLine(content, fonts.regular(), line, bodySize,
					page.getCropBox().getLowerLeftX() + style.leftMargin() + 4,
					y, style.bodyColor());
				y -= lineHeight(bodySize);
			}
			y -= Math.max(8, bodySize * 0.8f);
		}

		private void writePlainText(String text) throws IOException {
			if (text == null || text.isBlank()) {
				return;
			}
			for (String paragraph : text.strip().split("\\R", -1)) {
				if (paragraph.isBlank()) {
					y -= lineHeight(bodySize) * 0.55f;
					continue;
				}
				for (String line : wrap(fonts.regular(), paragraph, bodySize, contentWidth())) {
					ensureSpace(lineHeight(bodySize));
					writeLine(content, fonts.regular(), line, bodySize,
						page.getCropBox().getLowerLeftX() + style.leftMargin(),
						y, style.bodyColor());
					y -= lineHeight(bodySize);
				}
				y -= bodySize * 0.55f;
			}
		}

		private void ensureSpace(float requiredHeight) throws IOException {
			float lowerLimit = page.getCropBox().getLowerLeftY() + style.bottomMargin();
			if (y - requiredHeight < lowerLimit) {
				startPage();
			}
		}

		private float contentWidth() {
			return Math.max(120, page.getCropBox().getWidth()
				- style.leftMargin() - style.rightMargin());
		}

		@Override
		public void close() throws IOException {
			if (content != null) {
				content.close();
				content = null;
			}
		}
	}

	private List<String> wrap(PDFont font, String text, float size, float width) throws IOException {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int lastBreak = -1;
		for (int offset = 0; offset < text.length();) {
			int codePoint = text.codePointAt(offset);
			String next = new String(Character.toChars(codePoint));
			String candidate = line + next;
			if (!line.isEmpty() && textWidth(font, candidate, size) > width) {
				if (lastBreak > 0) {
					lines.add(line.substring(0, lastBreak).stripTrailing());
					String remainder = line.substring(lastBreak).stripLeading();
					line.setLength(0);
					line.append(remainder);
				} else {
					lines.add(line.toString());
					line.setLength(0);
				}
				lastBreak = -1;
			}
			line.append(next);
			if (Character.isWhitespace(codePoint)) {
				lastBreak = line.length();
			}
			offset += Character.charCount(codePoint);
		}
		if (!line.isEmpty()) {
			lines.add(line.toString().stripTrailing());
		}
		return lines;
	}

	private float lineHeight(float fontSize) {
		return fontSize * 1.55f;
	}
}
