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
	 *   <li>append_paragraph: text, styleRole</li>
	 * </ul>
	 *
	 * <p>{@code styleRole}은 모델이 글꼴이나 크기를 임의로 정하는 값이 아닙니다.
	 * 서버가 원본 문서 안에서 역할이 같은 문단을 찾아 실제 서식을 상속하기 위한
	 * 의미 힌트이며, 지원 값은 {@code auto/title/body/list}입니다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Op(String op, String from, String to, String contains, String text, String styleRole) {
		/** 기존 AI 응답과 저장된 테스트 지시의 하위 호환성을 유지합니다. */
		public Op(String op, String from, String to, String contains, String text) {
			this(op, from, to, contains, text, null);
		}
	}
}
