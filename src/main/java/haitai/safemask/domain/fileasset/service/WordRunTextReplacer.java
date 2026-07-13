package haitai.safemask.domain.fileasset.service;

import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

/** Word 문단의 run 서식을 유지하면서 여러 run에 걸친 문자열을 치환하는 공통 유틸리티입니다. */
public final class WordRunTextReplacer {

	private WordRunTextReplacer() {
	}

	public static int replace(XWPFParagraph paragraph, String from, String to) {
		if (from == null || from.isEmpty() || to == null) {
			return 0;
		}
		List<XWPFRun> runs = paragraph.getRuns();
		if (runs.isEmpty()) {
			return 0;
		}
		StringBuilder full = new StringBuilder();
		List<Integer> owners = new ArrayList<>();
		for (int runIndex = 0; runIndex < runs.size(); runIndex++) {
			// run.text()에는 탭·줄바꿈 같은 비텍스트 XML 노드도 합쳐집니다. 이를 T 노드에
			// 다시 쓰면 원래 탭/줄바꿈 노드와 중복되므로 실제 텍스트 노드만 재구성합니다.
			String text = runs.get(runIndex).getCTR().getTList().stream()
				.map(node -> node.getStringValue() == null ? "" : node.getStringValue())
				.reduce("", String::concat);
			full.append(text);
			for (int i = 0; i < text.length(); i++) {
				owners.add(runIndex);
			}
		}
		if (full.indexOf(from) < 0) {
			return 0;
		}

		List<StringBuilder> rewritten = new ArrayList<>();
		for (int i = 0; i < runs.size(); i++) {
			rewritten.add(new StringBuilder());
		}
		int replacements = 0;
		for (int cursor = 0; cursor < full.length();) {
			if (full.indexOf(from, cursor) == cursor) {
				rewritten.get(owners.get(cursor)).append(to);
				cursor += from.length();
				replacements++;
			} else {
				rewritten.get(owners.get(cursor)).append(full.charAt(cursor));
				cursor++;
			}
		}
		for (int i = 0; i < runs.size(); i++) {
			setRunText(runs.get(i), rewritten.get(i).toString());
		}
		return replacements;
	}

	private static void setRunText(XWPFRun run, String text) {
		int textNodeCount = run.getCTR().sizeOfTArray();
		if (textNodeCount == 0) {
			run.setText(text);
			return;
		}
		run.setText(text, 0);
		for (int i = textNodeCount - 1; i > 0; i--) {
			run.getCTR().removeT(i);
		}
	}
}
