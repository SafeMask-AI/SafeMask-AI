package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.fileasset.dto.WordEditInstruction;
import haitai.safemask.domain.fileasset.dto.WordEditInstruction.Op;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBody;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/** 원본 docx의 카피에 제한된 편집 지시를 적용하면서 건드리지 않은 구조와 서식을 보존합니다. */
@Component
public class WordEditExecutor {

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
			replacements += WordRunTextReplacer.replace(paragraph, op.from(), op.to());
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
		document.createParagraph().createRun().setText(op.text());
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
