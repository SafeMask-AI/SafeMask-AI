package haitai.safemask.domain.fileasset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.fileasset.dto.WordEditInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** AI 응답의 SAFEMASK_WORD_EDIT 블록을 Word 편집 지시로 변환합니다. */
@Component
@RequiredArgsConstructor
public class AiWordEditBlockParser {

	private static final Pattern BLOCK = Pattern.compile(
		"\\[\\[SAFEMASK_WORD_EDIT\\s+file=\"([^\"\\n]{1,200})\"\\]\\]\\s*\\n"
			+ "([\\s\\S]*?)\\n?\\[\\[/SAFEMASK_WORD_EDIT\\]\\]");

	private final ObjectMapper objectMapper;

	public record WordEditBlock(String targetFileName, WordEditInstruction instruction, int start, int end) {
	}

	public List<WordEditBlock> parse(String text) {
		if (text == null || !text.contains("[[SAFEMASK_WORD_EDIT")) {
			return List.of();
		}
		List<WordEditBlock> blocks = new ArrayList<>();
		Matcher matcher = BLOCK.matcher(text);
		while (matcher.find()) {
			blocks.add(new WordEditBlock(matcher.group(1).trim(), parseInstruction(matcher.group(2)),
				matcher.start(), matcher.end()));
		}
		return blocks;
	}

	private WordEditInstruction parseInstruction(String json) {
		try {
			return objectMapper.readValue(json.trim(), WordEditInstruction.class);
		} catch (JsonProcessingException e) {
			return null;
		}
	}
}
