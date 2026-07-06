package haitai.safemask.domain.fileasset.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * AI 응답 속 파일 블록을 찾아 파싱합니다.
 *
 * <p>GPT는 시스템 프롬프트의 [파일 생성 규칙]에 따라 파일을 만들 때
 * 아래 형식의 블록을 응답에 포함합니다.
 * <pre>
 * [[SAFEMASK_FILE name="정리본.xlsx"]]
 * 이름,휴대폰
 * 김민준,010-2345-6789
 * [[/SAFEMASK_FILE]]
 * </pre>
 * 블록 본문은 CSV이며(따옴표 이스케이프 지원), GPT가 블록을 마크다운
 * 코드펜스(```)로 감싸는 경우가 흔해 펜스도 블록의 일부로 함께 소거합니다.
 */
@Component
public class AiFileBlockParser {

	/**
	 * 블록 전체 매칭 패턴.
	 * 그룹1=파일명, 그룹2=본문. 앞뒤의 코드펜스(```)는 있으면 함께 매칭해 제거합니다.
	 */
	private static final Pattern FILE_BLOCK = Pattern.compile(
		"(?:```[a-zA-Z0-9]*\\s*\\n)?"
			+ "\\[\\[SAFEMASK_FILE\\s+name=\"([^\"\\n]{1,200})\"\\]\\]\\s*\\n"
			+ "(.*?)"
			+ "\\n?\\[\\[/SAFEMASK_FILE\\]\\]"
			+ "(?:\\s*\\n?```)?",
		Pattern.DOTALL);

	/**
	 * 파싱된 파일 블록 하나.
	 *
	 * @param fileName 모델이 지정한 파일명 (검증·정제 전 원본)
	 * @param body     블록 본문 (CSV 또는 일반 텍스트)
	 * @param start    응답 텍스트 내 블록 시작 인덱스 (치환용)
	 * @param end      응답 텍스트 내 블록 끝 인덱스 (치환용)
	 */
	public record FileBlock(String fileName, String body, int start, int end) {
	}

	/** 응답 텍스트에서 모든 파일 블록을 등장 순서대로 찾습니다. */
	public List<FileBlock> parse(String text) {
		List<FileBlock> blocks = new ArrayList<>();
		if (text == null || !text.contains("[[SAFEMASK_FILE")) {
			return blocks;
		}

		Matcher matcher = FILE_BLOCK.matcher(text);
		while (matcher.find()) {
			blocks.add(new FileBlock(matcher.group(1).trim(), matcher.group(2), matcher.start(), matcher.end()));
		}
		return blocks;
	}

	/**
	 * CSV 본문을 행/셀 2차원 리스트로 파싱합니다.
	 * RFC 4180 기준: 쉼표 구분, 큰따옴표로 감싼 셀 안에서는 쉼표·줄바꿈 허용,
	 * 따옴표 자체는 ""로 이스케이프.
	 */
	public List<List<String>> parseCsv(String body) {
		List<List<String>> rows = new ArrayList<>();
		List<String> currentRow = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean inQuotes = false;

		for (int i = 0; i < body.length(); i++) {
			char ch = body.charAt(i);

			if (inQuotes) {
				if (ch == '"') {
					// ""는 이스케이프된 따옴표, 단독 "는 셀 닫기
					if (i + 1 < body.length() && body.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					} else {
						inQuotes = false;
					}
				} else {
					cell.append(ch);
				}
				continue;
			}

			switch (ch) {
				case '"' -> inQuotes = true;
				case ',' -> {
					currentRow.add(cell.toString());
					cell.setLength(0);
				}
				case '\r' -> {
					// CRLF의 CR은 무시 (LF에서 행 처리)
				}
				case '\n' -> {
					currentRow.add(cell.toString());
					cell.setLength(0);
					rows.add(currentRow);
					currentRow = new ArrayList<>();
				}
				default -> cell.append(ch);
			}
		}

		// 마지막 행 (개행 없이 끝나는 경우)
		if (cell.length() > 0 || !currentRow.isEmpty()) {
			currentRow.add(cell.toString());
			rows.add(currentRow);
		}

		// 완전히 빈 행 제거 (GPT가 블록 끝에 빈 줄을 넣는 경우가 많음)
		rows.removeIf(row -> row.stream().allMatch(String::isBlank));
		return rows;
	}
}
