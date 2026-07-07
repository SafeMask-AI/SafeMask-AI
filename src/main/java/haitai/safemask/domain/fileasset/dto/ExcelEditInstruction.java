package haitai.safemask.domain.fileasset.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * AI가 [[SAFEMASK_EDIT]] 블록에 담아 보내는 엑셀 편집 지시입니다.
 *
 * <p>모델은 마스킹된 텍스트만 보고 "무엇을 어떻게 바꿀지"만 선언하고,
 * 실제 실행은 서버가 원본 파일의 실제 값으로 수행합니다.
 * 이 구조 덕분에 마스킹된 컬럼 기준의 정렬·필터도 가능합니다.
 * (모델이 값을 몰라도, 서버는 원본을 알기 때문)
 *
 * @param result 결과 파일명 (생략 시 "원본명_수정.xlsx")
 * @param ops    적용할 편집 연산 목록 (순서대로 적용)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExcelEditInstruction(
	String result,
	List<Op> ops
) {

	/**
	 * 편집 연산 하나. op 종류에 따라 사용하는 필드가 다릅니다.
	 * <ul>
	 *   <li>delete_column: column</li>
	 *   <li>rename_column: from, to</li>
	 *   <li>filter_rows: column + (contains | notContains | equals) 중 하나 — 조건에 맞는 행만 남김</li>
	 *   <li>sort: column + order(asc|desc, 기본 asc)</li>
	 *   <li>replace_value: from, to (+ column 선택) — 셀 텍스트 속 from을 to로 치환</li>
	 *   <li>add_row: values — 마지막 데이터 행 아래에 새 행 추가 (기존 행 서식을 그대로 입힘)</li>
	 * </ul>
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Op(
		String op,
		String column,
		String from,
		String to,
		String contains,
		String notContains,
		String equals,
		String order,
		List<String> values
	) {
	}
}
