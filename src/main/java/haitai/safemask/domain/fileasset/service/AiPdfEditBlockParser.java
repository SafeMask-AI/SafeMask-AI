package haitai.safemask.domain.fileasset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import haitai.safemask.domain.fileasset.dto.PdfEditInstruction;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** AI 응답의 SAFEMASK_PDF_EDIT 블록을 PDF 편집 지시로 변환합니다. */
@Component
@RequiredArgsConstructor
public class AiPdfEditBlockParser {
	private static final Pattern BLOCK = Pattern.compile(
		"\\[\\[SAFEMASK_PDF_EDIT\\s+file=\"([^\"\\n]{1,200})\"\\]\\]\\s*\\n([\\s\\S]*?)\\n?\\[\\[/SAFEMASK_PDF_EDIT\\]\\]");
	private final ObjectMapper objectMapper;
	public record PdfEditBlock(String targetFileName, PdfEditInstruction instruction, int start, int end) {}
	public List<PdfEditBlock> parse(String text) {
		if (text == null || !text.contains("[[SAFEMASK_PDF_EDIT")) return List.of();
		List<PdfEditBlock> result = new ArrayList<>();
		Matcher matcher = BLOCK.matcher(text);
		while (matcher.find()) result.add(new PdfEditBlock(matcher.group(1).trim(), parseInstruction(matcher.group(2)), matcher.start(), matcher.end()));
		return result;
	}
	private PdfEditInstruction parseInstruction(String json) {
		try { return objectMapper.readValue(json.trim(), PdfEditInstruction.class); }
		catch (JsonProcessingException e) { return null; }
	}
}
