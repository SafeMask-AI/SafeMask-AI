package haitai.safemask.domain.fileasset.service;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/**
 * 원본 PDF에서 외부 전송 없이 새 페이지 렌더링에 필요한 디자인 프로필을 추출합니다.
 *
 * <p>PDF는 의미 스타일이 없는 고정 좌표 문서이므로 텍스트의 실제 표시 위치·glyph 높이·색상과
 * 기존 페이지 번호를 통계적으로 분석합니다. 원문 문자열은 분석 중 메모리에서만 사용하며
 * AI 요청이나 로그에는 포함하지 않습니다.
 */
final class PdfStyleAnalyzer {
	private static final float MIN_MARGIN = 10;
	private static final float MAX_MARGIN = 72;
	private static final Pattern PAGE_NUMBER = Pattern.compile("\\d+\\s*/\\s*\\d+");
	private static final Color DEFAULT_PRIMARY = new Color(31, 59, 102);
	private static final Color DEFAULT_BODY = new Color(35, 35, 35);
	private static final Color DEFAULT_MUTED = new Color(110, 118, 130);

	PdfStyleProfile analyze(PDDocument document) throws IOException {
		List<TextSample> samples = new ArrayList<>();
		List<PageNumberMark> pageNumbers = new ArrayList<>();
		for (int i = 0; i < document.getNumberOfPages(); i++) {
			PDPage page = document.getPage(i);
			PageTextCollector collector = new PageTextCollector(i, page.getCropBox());
			collector.setSortByPosition(true);
			collector.setStartPage(i + 1);
			collector.setEndPage(i + 1);
			collector.getText(document);
			samples.addAll(collector.samples());
			pageNumbers.addAll(collector.pageNumbers());
		}

		PDPage reference = document.getPage(document.getNumberOfPages() - 1);
		PDRectangle crop = reference.getCropBox();
		List<TextSample> contentSamples = samples.stream()
			.filter(sample -> !PAGE_NUMBER.matcher(sample.text().trim()).matches())
			.filter(sample -> sample.topY() < crop.getHeight() * 0.9f)
			.toList();
		if (contentSamples.isEmpty()) {
			return PdfStyleProfile.defaults(reference, pageNumbers);
		}

		TextSample title = contentSamples.stream()
			.filter(sample -> sample.topY() < crop.getHeight() * 0.35f)
			.max(Comparator.comparingDouble(TextSample::fontSize))
			.orElse(contentSamples.get(0));
		float left = clamp(contentSamples.stream().map(TextSample::x).min(Float::compare).orElse(MIN_MARGIN),
			MIN_MARGIN, MAX_MARGIN);
		// 텍스트의 끝점은 짧은 문장이나 폭이 좁은 표의 끝일 수 있으므로 여백으로 쓰지 않습니다.
		// 보고서·양식에서 실제로 관측한 왼쪽 시작점을 대칭 콘텐츠 경계로 사용합니다.
		float right = left;
		float bodySize = dominantBodySize(contentSamples, title);
		Color bodyColor = dominantColor(contentSamples.stream()
			.filter(sample -> sample.fontSize() <= title.fontSize() * 0.9f)
			.toList(), DEFAULT_BODY);
		Color primary = usablePrimary(title.color()) ? title.color() : DEFAULT_PRIMARY;
		Color muted = dominantMutedColor(contentSamples, primary, bodyColor);
		float top = clamp(Math.max(MIN_MARGIN, title.topY()), MIN_MARGIN, MAX_MARGIN);
		float bottom = pageNumbers.isEmpty()
			? 30
			: clamp(pageNumbers.get(0).baselineFromBottom() + 12, 30, MAX_MARGIN);
		float titleSize = clamp(title.fontSize(), Math.max(14, bodySize + 3), 26);
		float sectionSize = clamp(Math.max(bodySize, titleSize * 0.58f), 9, 14);
		float pageNumberSize = pageNumbers.isEmpty() ? 9 : pageNumbers.get(0).fontSize();

		return new PdfStyleProfile(copyRectangle(reference.getMediaBox()), copyRectangle(crop),
			reference.getRotation(), left, right, top, bottom, titleSize, bodySize, sectionSize,
			pageNumberSize, primary, bodyColor, muted, tint(primary, 0.92f), List.copyOf(pageNumbers));
	}

	private float dominantBodySize(List<TextSample> samples, TextSample title) {
		Map<Integer, Integer> weights = new LinkedHashMap<>();
		for (TextSample sample : samples) {
			if (sample == title || sample.fontSize() >= title.fontSize() * 0.95f) {
				continue;
			}
			int tenth = Math.round(sample.fontSize() * 10);
			weights.merge(tenth, Math.max(1, sample.text().length()), Integer::sum);
		}
		return weights.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(entry -> clamp(entry.getKey() / 10f, 8, 14))
			.orElse(11f);
	}

	private Color dominantColor(List<TextSample> samples, Color fallback) {
		Map<Integer, Integer> weights = new HashMap<>();
		for (TextSample sample : samples) {
			weights.merge(sample.color().getRGB(), Math.max(1, sample.text().length()), Integer::sum);
		}
		return weights.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(entry -> new Color(entry.getKey(), true))
			.orElse(fallback);
	}

	private Color dominantMutedColor(List<TextSample> samples, Color primary, Color body) {
		return samples.stream()
			.map(TextSample::color)
			.filter(color -> colorDistance(color, primary) > 45)
			.filter(color -> colorDistance(color, body) > 25)
			.min(Comparator.comparingInt(this::brightness))
			.orElse(DEFAULT_MUTED);
	}

	private boolean usablePrimary(Color color) {
		// 검정 제목도 유효한 문서 디자인입니다. 흰색에 가까워 구분선·제목이 보이지 않는
		// 경우만 기본 색상으로 대체합니다.
		return color != null && brightness(color) < 720;
	}

	private int brightness(Color color) {
		return color.getRed() + color.getGreen() + color.getBlue();
	}

	private int colorDistance(Color first, Color second) {
		return Math.abs(first.getRed() - second.getRed())
			+ Math.abs(first.getGreen() - second.getGreen())
			+ Math.abs(first.getBlue() - second.getBlue());
	}

	private Color tint(Color color, float whiteRatio) {
		return new Color(
			Math.round(color.getRed() * (1 - whiteRatio) + 255 * whiteRatio),
			Math.round(color.getGreen() * (1 - whiteRatio) + 255 * whiteRatio),
			Math.round(color.getBlue() * (1 - whiteRatio) + 255 * whiteRatio));
	}

	private static PDRectangle copyRectangle(PDRectangle source) {
		return new PDRectangle(source.getLowerLeftX(), source.getLowerLeftY(),
			source.getWidth(), source.getHeight());
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	record PageNumberMark(int pageIndex, float x, float topY, float width, float height,
						  float fontSize, Color color, float pageWidth,
						  float baselineFromBottom) {
	}

	record PdfStyleProfile(PDRectangle mediaBox, PDRectangle cropBox, int rotation,
						   float leftMargin, float rightMargin, float topMargin,
						   float bottomMargin, float titleSize, float bodySize,
						   float sectionSize, float pageNumberSize, Color primaryColor,
						   Color bodyColor, Color mutedColor, Color sectionFill,
						   List<PageNumberMark> pageNumbers) {
		private static PdfStyleProfile defaults(PDPage page, List<PageNumberMark> pageNumbers) {
			PDRectangle media = page.getMediaBox();
			PDRectangle crop = page.getCropBox();
			return new PdfStyleProfile(copyRectangle(media), copyRectangle(crop), page.getRotation(),
				48, 48, 42, 36, 18, 11, 11, 9,
				DEFAULT_PRIMARY, DEFAULT_BODY, DEFAULT_MUTED,
				new Color(237, 241, 247), List.copyOf(pageNumbers));
		}
	}

	private record TextSample(String text, float x, float topY, float width,
							  float height, float fontSize, Color color) {
	}

	private static final class PageTextCollector extends PDFTextStripper {
		private final int pageIndex;
		private final PDRectangle crop;
		private final List<TextSample> samples = new ArrayList<>();
		private final List<PageNumberMark> pageNumbers = new ArrayList<>();
		private final Map<String, Color> positionColors = new HashMap<>();
		private Color activeGlyphColor = DEFAULT_BODY;
		private Color streamTextColor = DEFAULT_BODY;
		private final Deque<Color> colorStack = new ArrayDeque<>();

		private PageTextCollector(int pageIndex, PDRectangle crop) throws IOException {
			this.pageIndex = pageIndex;
			this.crop = crop;
		}

		@Override
		protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
			switch (operator.getName()) {
				case "q" -> colorStack.push(streamTextColor);
				case "Q" -> streamTextColor = colorStack.isEmpty() ? DEFAULT_BODY : colorStack.pop();
				case "g" -> streamTextColor = gray(operands);
				case "rg" -> streamTextColor = rgb(operands);
				case "k" -> streamTextColor = cmyk(operands);
				case "sc", "scn" -> streamTextColor = inferredColor(operands, streamTextColor);
				default -> {
					// 텍스트 색상과 관계없는 PDF 연산은 상위 엔진에 그대로 위임합니다.
				}
			}
			super.processOperator(operator, operands);
		}

		@Override
		protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code,
								 Vector displacement) throws IOException {
			activeGlyphColor = streamTextColor;
			super.showGlyph(textRenderingMatrix, font, code, displacement);
		}

		@Override
		protected void processTextPosition(TextPosition text) {
			// writeString은 줄 조합이 끝난 뒤 호출되므로 glyph 처리 시점의 색상을 좌표에 연결합니다.
			positionColors.put(positionKey(text), activeGlyphColor);
			super.processTextPosition(text);
		}

		@Override
		protected void writeString(String text, List<TextPosition> positions) {
			if (text == null || text.isBlank() || positions == null || positions.isEmpty()) {
				return;
			}
			TextPosition first = positions.get(0);
			TextPosition last = positions.get(positions.size() - 1);
			float x = first.getXDirAdj();
			float topY = first.getYDirAdj();
			float width = last.getXDirAdj() + last.getWidthDirAdj() - x;
			float height = positions.stream().map(TextPosition::getHeightDir).max(Float::compare).orElse(10f);
			// 선언 fontSize가 변환 행렬 때문에 실제 표시 크기보다 크게 보고되는 PDF가 있어
			// 화면상 glyph 높이를 기준으로 렌더링 크기를 보정합니다.
			float fontSize = clamp(height * 1.2f, 6, 26);
			Color color = positionColors.getOrDefault(positionKey(first), DEFAULT_BODY);
			String normalized = text.trim();
			samples.add(new TextSample(normalized, x, topY, width, height, fontSize, color));
			if (PAGE_NUMBER.matcher(normalized).matches() && topY > crop.getHeight() * 0.78f) {
				pageNumbers.add(new PageNumberMark(pageIndex, x, topY, width, height, fontSize, color,
					crop.getWidth(), crop.getHeight() - topY));
			}
		}

		private Color inferredColor(List<COSBase> operands, Color fallback) {
			return switch (operands.size()) {
				case 1 -> gray(operands);
				case 3 -> rgb(operands);
				case 4 -> cmyk(operands);
				default -> fallback;
			};
		}

		private Color gray(List<COSBase> operands) {
			float gray = component(operands, 0);
			return new Color(gray, gray, gray);
		}

		private Color rgb(List<COSBase> operands) {
			return new Color(component(operands, 0), component(operands, 1), component(operands, 2));
		}

		private Color cmyk(List<COSBase> operands) {
			float c = component(operands, 0);
			float m = component(operands, 1);
			float y = component(operands, 2);
			float k = component(operands, 3);
			return new Color(1 - Math.min(1, c + k), 1 - Math.min(1, m + k),
				1 - Math.min(1, y + k));
		}

		private float component(List<COSBase> operands, int index) {
			if (index >= operands.size() || !(operands.get(index) instanceof COSNumber number)) {
				return 0;
			}
			return Math.max(0, Math.min(1, number.floatValue()));
		}

		private String positionKey(TextPosition position) {
			return position.getXDirAdj() + ":" + position.getYDirAdj() + ":" + position.getUnicode();
		}

		private List<TextSample> samples() {
			return samples;
		}

		private List<PageNumberMark> pageNumbers() {
			return pageNumbers;
		}
	}
}
