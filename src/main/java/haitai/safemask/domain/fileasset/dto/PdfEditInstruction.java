package haitai.safemask.domain.fileasset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** 원본 PDF 복사본에 적용할 안전한 페이지 단위 편집 지시입니다. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfEditInstruction(String result, List<Op> ops) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Op(String op, String title, String text, Integer page, Double fontSize) {
	}
}
