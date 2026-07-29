package haitai.safemask.domain.fileasset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 원본 PDF 복사본에 적용할 안전한 페이지 단위 편집 지시입니다.
 *
 * <p>색상·좌표·글꼴명은 AI가 지정하지 않습니다. 서버가 원본 PDF에서 안전하게
 * 추출한 디자인 프로필을 적용하며, AI는 제목·본문·섹션 같은 의미 구조만 선언합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PdfEditInstruction(String result, List<Op> ops) {
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Op(String op, String title, String text, Integer page, Double fontSize,
					 String subtitle, List<Section> sections) {
		/** 기존 AI 응답과 저장된 테스트 지시의 하위 호환성을 유지합니다. */
		public Op(String op, String title, String text, Integer page, Double fontSize) {
			this(op, title, text, page, fontSize, null, null);
		}
	}

	/**
	 * 요약 페이지 안의 의미 단위입니다. {@code items}는 글머리표 목록으로 렌더링하고,
	 * {@code text}는 일반 문단으로 렌더링합니다.
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Section(String heading, String text, List<String> items) {
	}
}
