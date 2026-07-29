package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.fileasset.dto.WordEditInstruction;
import haitai.safemask.domain.fileasset.dto.WordEditInstruction.Op;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.springframework.stereotype.Component;

/**
 * 원본 docx의 카피에 제한된 편집 지시를 적용하면서 건드리지 않은 구조와 서식을 보존합니다.
 *
 * <p>새 문단은 기본 Word 서식으로 만들지 않고 원본 문서 안에서 같은 역할의 문단을 찾아
 * 문단 속성과 run 속성을 복사합니다. AI는 title/body/list 같은 의미 역할만 지정하며,
 * 실제 글꼴·색상·간격·번호 체계는 외부로 보내지 않은 원본에서 서버가 결정합니다.
 */
@Component
public class WordEditExecutor {
	private static final Set<String> STYLE_ROLES = Set.of("auto", "title", "body", "list");
	private static final int MAX_OPS = 50;
	private static final int MAX_APPENDED_CHARS = 60_000;

	public static class UnsupportedWordEditException extends RuntimeException {
		public UnsupportedWordEditException(String message) {
			super(message);
		}
	}

	public byte[] apply(byte[] originalBytes, WordEditInstruction instruction) {
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(originalBytes));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			validate(instruction);
			for (Op op : instruction.ops()) {
				apply(document, op);
			}
			document.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private void validate(WordEditInstruction instruction) {
		if (instruction == null || instruction.ops() == null || instruction.ops().isEmpty()) {
			throw new UnsupportedWordEditException("적용할 Word 편집 내용이 없습니다");
		}
		if (instruction.ops().size() > MAX_OPS) {
			throw new UnsupportedWordEditException(
				"Word 편집 작업은 한 번에 최대 " + MAX_OPS + "개까지 가능합니다");
		}
		int appendedChars = instruction.ops().stream()
			.filter(op -> op != null && "append_paragraph".equals(op.op()) && op.text() != null)
			.mapToInt(op -> op.text().length())
			.sum();
		if (appendedChars > MAX_APPENDED_CHARS) {
			throw new UnsupportedWordEditException("한 번에 추가할 Word 내용이 너무 깁니다");
		}
	}

	private void apply(XWPFDocument document, Op op) {
		if (op == null || op.op() == null) {
			throw new UnsupportedWordEditException("알 수 없는 Word 편집 지시가 있습니다");
		}
		switch (op.op()) {
			case "replace_text" -> replaceText(document, op);
			case "delete_paragraph" -> deleteParagraphs(document, op);
			case "append_paragraph" -> appendParagraph(document, op);
			default -> throw new UnsupportedWordEditException("지원하지 않는 Word 편집 지시입니다: " + op.op());
		}
	}

	private void replaceText(XWPFDocument document, Op op) {
		if (op.from() == null || op.from().isBlank() || op.to() == null) {
			throw new UnsupportedWordEditException("텍스트 치환에는 from과 to가 모두 필요합니다");
		}
		int replacements = 0;
		for (XWPFParagraph paragraph : allParagraphs(document)) {
			if (isAttached(paragraph)) {
				replacements += WordRunTextReplacer.replace(paragraph, op.from(), op.to());
			}
		}
		if (replacements == 0) {
			throw new UnsupportedWordEditException("바꿀 문구를 Word 파일에서 찾지 못했습니다: " + op.from());
		}
	}

	private void deleteParagraphs(XWPFDocument document, Op op) {
		if (op.contains() == null || op.contains().isBlank()) {
			throw new UnsupportedWordEditException("문단 삭제에는 contains가 필요합니다");
		}
		List<XWPFParagraph> targets = allParagraphs(document).stream()
			.filter(this::isAttached)
			.filter(paragraph -> paragraph.getText().contains(op.contains()))
			.toList();
		if (targets.isEmpty()) {
			throw new UnsupportedWordEditException("삭제할 문단을 Word 파일에서 찾지 못했습니다: " + op.contains());
		}
		for (XWPFParagraph paragraph : targets) {
			paragraph.getCTP().getDomNode().getParentNode().removeChild(paragraph.getCTP().getDomNode());
		}
	}

	private void appendParagraph(XWPFDocument document, Op op) {
		if (op.text() == null || op.text().isBlank()) {
			throw new UnsupportedWordEditException("문단 추가에는 text가 필요합니다");
		}
		String styleRole = normalizeStyleRole(op.styleRole());
		XWPFParagraph template = resolveTemplateParagraph(document, styleRole);
		XWPFParagraph appended = document.createParagraph();
		if (template != null) {
			copyParagraphProperties(template, appended);
		}

		XWPFRun run = appended.createRun();
		XWPFRun templateRun = representativeRun(template);
		if (templateRun != null && templateRun.getCTR().isSetRPr()) {
			run.getCTR().setRPr((CTRPr) templateRun.getCTR().getRPr().copy());
		}
		writeMultiline(run, op.text());
	}

	private String normalizeStyleRole(String requested) {
		String role = requested == null || requested.isBlank()
			? "auto" : requested.trim().toLowerCase(Locale.ROOT);
		if (!STYLE_ROLES.contains(role)) {
			throw new UnsupportedWordEditException("지원하지 않는 Word 문단 스타일 역할입니다: " + requested);
		}
		return role;
	}

	private XWPFParagraph resolveTemplateParagraph(XWPFDocument document, String role) {
		List<XWPFParagraph> candidates = document.getParagraphs().stream()
			.filter(this::isAttached)
			.filter(paragraph -> !paragraph.getText().isBlank())
			.toList();
		if (candidates.isEmpty()) {
			return null;
		}
		return switch (role) {
			case "title" -> findTitleTemplate(candidates);
			case "body" -> findBodyTemplate(candidates);
			case "list" -> candidates.stream()
				.filter(paragraph -> paragraph.getNumID() != null)
				.reduce((first, second) -> second)
				.orElseGet(() -> findBodyTemplate(candidates));
			default -> candidates.get(candidates.size() - 1);
		};
	}

	private boolean isAttached(XWPFParagraph paragraph) {
		try {
			return paragraph.getCTP().getDomNode().getParentNode() != null;
		} catch (RuntimeException disconnectedXmlObject) {
			// POI는 XML 노드를 직접 삭제한 직후 문단 목록 캐시에 분리된 객체를 잠시 남길 수 있습니다.
			return false;
		}
	}

	/**
	 * Word 문서가 명명 스타일을 사용하면 이를 우선하고, 직접 서식만 사용한 문서는
	 * 글자 크기와 굵기를 함께 비교해 가장 제목다운 문단을 선택합니다.
	 */
	private XWPFParagraph findTitleTemplate(List<XWPFParagraph> candidates) {
		return candidates.stream()
			.max(Comparator.comparingDouble(this::titleScore))
			.orElse(candidates.get(0));
	}

	private double titleScore(XWPFParagraph paragraph) {
		String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase(Locale.ROOT);
		double score = style.contains("title") || style.contains("heading") || style.contains("제목")
			? 1_000 : 0;
		score += representativeFontSize(paragraph) * 10;
		if (paragraph.getRuns().stream().anyMatch(XWPFRun::isBold)) {
			score += 50;
		}
		return score;
	}

	private XWPFParagraph findBodyTemplate(List<XWPFParagraph> candidates) {
		List<XWPFParagraph> bodyCandidates = candidates.stream()
			.filter(paragraph -> paragraph.getNumID() == null)
			.filter(paragraph -> {
				String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase(Locale.ROOT);
				return !(style.contains("title") || style.contains("heading") || style.contains("제목"));
			})
			.toList();
		List<XWPFParagraph> source = bodyCandidates.isEmpty() ? candidates : bodyCandidates;
		// 문서 뒤에 내용을 추가하는 작업이므로 동일 역할 중 가장 가까운 마지막 문단을 사용합니다.
		return source.get(source.size() - 1);
	}

	private double representativeFontSize(XWPFParagraph paragraph) {
		return paragraph.getRuns().stream()
			.map(XWPFRun::getFontSizeAsDouble)
			.filter(size -> size != null && size > 0)
			.mapToDouble(Double::doubleValue)
			.max()
			.orElse(11);
	}

	private void copyParagraphProperties(XWPFParagraph source, XWPFParagraph target) {
		if (source.getCTP().isSetPPr()) {
			target.getCTP().setPPr((CTPPr) source.getCTP().getPPr().copy());
		}
	}

	private XWPFRun representativeRun(XWPFParagraph paragraph) {
		if (paragraph == null || paragraph.getRuns().isEmpty()) {
			return null;
		}
		return paragraph.getRuns().stream()
			.filter(run -> run.text() != null && !run.text().isBlank())
			.findFirst()
			.orElse(paragraph.getRuns().get(0));
	}

	private void writeMultiline(XWPFRun run, String text) {
		String[] lines = text.strip().split("\\R", -1);
		for (int i = 0; i < lines.length; i++) {
			if (i > 0) {
				run.addBreak();
			}
			run.setText(lines[i]);
		}
	}

	private List<XWPFParagraph> allParagraphs(XWPFDocument document) {
		List<XWPFParagraph> paragraphs = new ArrayList<>();
		collectBody(document, paragraphs);
		for (XWPFHeader header : document.getHeaderList()) {
			collectBody(header, paragraphs);
		}
		for (XWPFFooter footer : document.getFooterList()) {
			collectBody(footer, paragraphs);
		}
		return paragraphs;
	}

	private void collectBody(IBody body, List<XWPFParagraph> paragraphs) {
		paragraphs.addAll(body.getParagraphs());
		for (XWPFTable table : body.getTables()) {
			collectTable(table, paragraphs);
		}
	}

	private void collectTable(XWPFTable table, List<XWPFParagraph> paragraphs) {
		for (XWPFTableRow row : table.getRows()) {
			for (XWPFTableCell cell : row.getTableCells()) {
				collectBody(cell, paragraphs);
			}
		}
	}
}
