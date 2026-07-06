package haitai.safemask.domain.fileasset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.fileasset.dto.ExcelEditInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 응답 속 엑셀 편집 지시 블록을 찾아 파싱합니다.
 *
 * <p>GPT는 시스템 프롬프트의 [첨부 파일 수정 규칙]에 따라, 업로드된 파일의
 * 수정 요청에는 파일 내용을 다시 쓰는 대신 아래 형식의 지시 블록을 냅니다.
 * <pre>
 * [[SAFEMASK_EDIT file="고객명단.xlsx"]]
 * {"result": "고객명단_정리.xlsx",
 *  "ops": [{"op": "delete_column", "column": "주민등록번호"}]}
 * [[/SAFEMASK_EDIT]]
 * </pre>
 * 파일 생성 블록(AiFileBlockParser)과 마찬가지로 코드펜스로 감싸인 경우도 처리합니다.
 */
@Component
@RequiredArgsConstructor
public class AiEditBlockParser {

	/** 그룹1=대상 파일명, 그룹2=지시 JSON. 앞뒤 코드펜스는 있으면 함께 소거 대상에 포함 */
	private static final Pattern EDIT_BLOCK = Pattern.compile(
		"(?:```[a-zA-Z0-9]*\\s*\\n)?"
			+ "\\[\\[SAFEMASK_EDIT\\s+file=\"([^\"\\n]{1,200})\"\\]\\]\\s*\\n"
			+ "(.*?)"
			+ "\\n?\\[\\[/SAFEMASK_EDIT\\]\\]"
			+ "(?:\\s*\\n?```)?",
		Pattern.DOTALL);

	private final ObjectMapper objectMapper;

	/**
	 * 파싱된 편집 블록 하나.
	 *
	 * @param targetFileName 수정 대상 파일명 (모델이 지정, 업로드 파일과 매칭용)
	 * @param instruction    편집 지시 (JSON이 깨졌으면 null — 호출부에서 실패 처리)
	 * @param start          응답 텍스트 내 블록 시작 인덱스 (치환용)
	 * @param end            응답 텍스트 내 블록 끝 인덱스 (치환용)
	 */
	public record EditBlock(String targetFileName, ExcelEditInstruction instruction, int start, int end) {
	}

	/** 응답 텍스트에서 모든 편집 블록을 등장 순서대로 찾습니다. */
	public List<EditBlock> parse(String text) {
		List<EditBlock> blocks = new ArrayList<>();
		if (text == null || !text.contains("[[SAFEMASK_EDIT")) {
			return blocks;
		}

		Matcher matcher = EDIT_BLOCK.matcher(text);
		while (matcher.find()) {
			blocks.add(new EditBlock(matcher.group(1).trim(), parseInstruction(matcher.group(2)),
				matcher.start(), matcher.end()));
		}
		return blocks;
	}

	private ExcelEditInstruction parseInstruction(String json) {
		try {
			return objectMapper.readValue(json.trim(), ExcelEditInstruction.class);
		} catch (JsonProcessingException e) {
			// 모델이 JSON을 잘못 만든 경우. null을 반환해 해당 블록만 실패 안내로 치환하게 한다.
			return null;
		}
	}
}
