package haitai.safemask.domain.fileasset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * AI가 Word 원본의 카피에 적용하도록 선언하는 제한된 편집 지시입니다.
 * 모델에는 마스킹된 값만 보이지만 응답 원복 후 실행되므로, {@code from}/{@code contains}에는
 * 서버가 복원한 원본값이 들어옵니다. 파일 바이트 자체는 외부 AI로 전송하지 않습니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WordEditInstruction(String result, List<Op> ops) {

	/**
	 * 지원 연산:
	 * <ul>
	 *   <li>replace_text: from, to</li>
	 *   <li>delete_paragraph: contains</li>
	 *   <li>append_paragraph: text</li>
	 * </ul>
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Op(String op, String from, String to, String contains, String text) {
	}
}
