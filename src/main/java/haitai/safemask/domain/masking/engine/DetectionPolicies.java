package haitai.safemask.domain.masking.engine;

import haitai.safemask.domain.maskingrule.service.MaskingRuleSeeder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 정규식만으로는 확정할 수 없는 매칭을 최종 판정하는 표준 정책 모음입니다.
 * (MaskingEngine.DetectionFilter의 운영 구현 — 서비스와 테스트가 같은 정책을 공유)
 *
 * <p>규칙 이름별로 다른 판정을 적용합니다:
 * <ul>
 *   <li>이름(단독 표기)/이름(문장 속): "이름 컬럼의 값"이면 무조건 인정(정형 데이터 경로),
 *       아니면 이름 사전 검증(비정형 경로). 컬럼 헤더 단어("고객명", "신청자")나
 *       일반 단어("고마워")가 이름으로 오탐되는 것을 막으면서, 사전에 없는 희귀 이름도
 *       이름 컬럼 아래에 있으면 잡습니다.</li>
 *   <li>카드번호(무구분): Luhn 체크섬 검증 — 카드 번호대와 자릿수가 같아도
 *       체크섬이 틀린 일반 숫자열(주문번호 등)은 제외합니다.</li>
 *   <li>여권번호: 매칭 바로 앞 문맥에 제품/코드/품번/모델이 있으면 제품코드로 보고 제외합니다.</li>
 * </ul>
 */
public final class DetectionPolicies {

	/** 이 헤더가 가리키는 컬럼/다음 칸의 값은 이름으로 간주한다 */
	private static final Set<String> NAME_COLUMN_HEADERS = Set.of(
		"이름", "성명", "고객명", "담당자명", "작성자", "신청자", "수령인", "지원자", "지원자명",
		"담당자", "접수자", "처리자", "회원명", "기사명", "예약자", "수신자", "발신자");

	/** 이름 컬럼 값으로 인정하는 형태: 한글 2~4자 (성+이름) */
	private static final Pattern NAME_CELL_VALUE = Pattern.compile("[가-힣]{2,4}");

	/**
	 * 이름 컬럼에 있어도 값이 아니라 또 다른 라벨일 가능성이 큰 접미사.
	 * (예: 헤더 행의 "지원일", "연락처"가 이름 옆 칸에 있는 경우)
	 * 이 접미사로 끝나는 이름(상일, 도명 등)은 확정 경로 대신 이름 사전으로 판정됩니다.
	 */
	private static final Pattern HEADER_LIKE_VALUE = Pattern.compile(
		".*(명|일|번호|일자|주소|연락처|메일|내용|이유|판정|구분|상태|여부|비고|부서|팀|처|룰|코드|금액|수량|기간|시간|일시|항목|결과)$");

	/** 여권번호 매칭 앞에서 이만큼을 살펴 제품코드 문맥인지 판단한다 */
	private static final int PASSPORT_CONTEXT_WINDOW = 12;

	/** SQL 한정 식별자 규칙에서 예약어·함수명을 테이블/컬럼명으로 오탐하지 않기 위한 제외 목록 */
	private static final Set<String> SQL_RESERVED_WORDS = Set.of(
		"SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "ON",
		"AND", "OR", "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "INSERT", "INTO",
		"UPDATE", "DELETE", "VALUES", "SET", "AS", "DISTINCT", "COUNT", "SUM", "AVG", "MIN",
		"MAX", "DATE", "NOW", "CURRENT_DATE", "CURRENT_TIMESTAMP", "CASE", "WHEN", "THEN",
		"ELSE", "END", "NULL", "TRUE", "FALSE", "CAST", "COALESCE", "NVL", "SUBSTR", "CONCAT");

	private DetectionPolicies() {
	}

	/**
	 * 표 스캔 결과.
	 *
	 * @param confirmedNames 이름 컬럼/라벨의 값 — 사전에 없어도 이름으로 확정
	 * @param headerLabels   표 헤더 행의 라벨들 — 이름 모양이어도 이름이 아니라고 확정
	 */
	private record TableScan(Set<String> confirmedNames, Set<String> headerLabels) {
	}

	/**
	 * 운영 표준 판정 필터를 만듭니다.
	 *
	 * @param text       마스킹 대상 원문 (이름 컬럼 스캔·앞 문맥 검사에 사용)
	 * @param givenNames 이름 사전 (성을 뺀 이름 집합, 비어 있을 수 있음)
	 */
	public static MaskingEngine.DetectionFilter standard(String text, Set<String> givenNames) {
		TableScan scan = scanTables(text);

		return (rule, value, start) -> switch (rule.getName()) {
			case MaskingRuleSeeder.NAME_STANDALONE_RULE_NAME, MaskingRuleSeeder.NAME_IN_SENTENCE_RULE_NAME ->
				acceptName(rule.getName(), value, scan, givenNames);
			case MaskingRuleSeeder.CARD_CONTIGUOUS_RULE_NAME -> passesLuhn(value);
			case MaskingRuleSeeder.PASSPORT_RULE_NAME -> !isProductCodeContext(text, start);
			case MaskingRuleSeeder.SQL_QUALIFIED_IDENTIFIER_RULE_NAME -> hasSqlContext(text, start)
				&& isSqlIdentifier(value);
			default -> true;
		};
	}

	private static boolean acceptName(String ruleName, String value, TableScan scan, Set<String> givenNames) {
		// 표 헤더 행의 라벨("지원일", "고객명" 등)은 이름 모양이어도 이름이 아니다
		if (scan.headerLabels().contains(value)) {
			return false;
		}
		// 이름 컬럼/라벨의 값이면 사전에 없어도 확정 (정형 데이터 경로)
		if (scan.confirmedNames().contains(value)) {
			return true;
		}
		// 단독 표기 규칙은 사전이 비어 있으면(비정상 상태) 예전 휴리스틱만으로 동작시킨다
		if (MaskingRuleSeeder.NAME_STANDALONE_RULE_NAME.equals(ruleName) && givenNames.isEmpty()) {
			return true;
		}
		return isDictionaryName(value, givenNames);
	}

	/** "김수정" 같은 세 글자 성명 후보의 이름 부분("수정")이 사전에 있는지 */
	private static boolean isDictionaryName(String value, Set<String> givenNames) {
		return value.length() == 3 && givenNames.contains(value.substring(1));
	}

	/**
	 * 탭 구분 표에서 이름을 확정할 근거를 수집합니다. 두 가지 배치를 지원합니다:
	 * <ul>
	 *   <li>세로형: 블록 첫 행(헤더)의 이름 계열 컬럼 → 아래 행들의 해당 칸 값</li>
	 *   <li>가로형(라벨-값): 어느 행이든 "수령인[탭]남하늘"처럼 이름 라벨 바로 다음 칸 값</li>
	 * </ul>
	 * 블록 첫 행의 셀들은 헤더 라벨로 기록해, 데이터 행의 "이름" 같은 값이 헤더로
	 * 오인되거나(검증표) 헤더 라벨("지원일")이 이름으로 오탐되는 것을 막습니다.
	 * 표 블록은 탭 없는 줄에서 끝납니다.
	 */
	private static TableScan scanTables(String text) {
		Set<String> confirmedNames = new HashSet<>();
		Set<String> headerLabels = new HashSet<>();
		List<Integer> nameColumns = List.of();
		boolean firstRowOfBlock = true;

		for (String line : text.split("\n", -1)) {
			if (!line.contains("\t")) {
				nameColumns = List.of();
				firstRowOfBlock = true;
				continue;
			}
			String[] cells = line.split("\t", -1);

			// 가로형: 이름 라벨 바로 다음 칸의 값 (헤더 행에서도 유효 — "이름  김민준  부서" 형태)
			for (int i = 0; i < cells.length - 1; i++) {
				if (isNameHeader(cells[i].trim())) {
					String next = cells[i + 1].trim();
					if (isPlausibleNameValue(next)) {
						confirmedNames.add(next);
					}
				}
			}

			if (firstRowOfBlock) {
				firstRowOfBlock = false;
				// 세로형 헤더는 "블록 첫 행이면서 헤더처럼 보이는 행"만 인정.
				// (데이터 행의 "이름" 값을 헤더로 오인하거나, 헤더 없는 명단 표의
				// 첫 행 이름을 라벨로 오인해 놓치는 것을 모두 방지)
				if (looksLikeHeaderRow(cells)) {
					nameColumns = findNameHeaderColumns(cells);
					for (String cell : cells) {
						String label = cell.trim();
						// 가로형에서 이름 값으로 확정된 칸은 라벨이 아니다
						if (NAME_CELL_VALUE.matcher(label).matches() && !confirmedNames.contains(label)) {
							headerLabels.add(label);
						}
					}
					continue;
				}
			}

			for (int column : nameColumns) {
				if (column < cells.length && isPlausibleNameValue(cells[column].trim())) {
					confirmedNames.add(cells[column].trim());
				}
			}
		}
		return new TableScan(confirmedNames, headerLabels);
	}

	/**
	 * 헤더처럼 보이는 행인지: 이름 계열 헤더나 라벨형 단어(~명, ~번호, ~일자 등)가
	 * 하나라도 있어야 헤더 행으로 봅니다. 전부 값처럼 보이면(이름·번호만 나열) 데이터 행입니다.
	 */
	private static boolean looksLikeHeaderRow(String[] cells) {
		for (String cell : cells) {
			String trimmed = cell.trim();
			if (isNameHeader(trimmed)
				|| (trimmed.matches("[가-힣]{1,6}") && HEADER_LIKE_VALUE.matcher(trimmed).matches())) {
				return true;
			}
		}
		return false;
	}

	/** 이름 값으로 확정해도 되는 형태인지: 한글 2~4자이면서 라벨(다른 헤더)일 가능성이 낮아야 함 */
	private static boolean isPlausibleNameValue(String cell) {
		return NAME_CELL_VALUE.matcher(cell).matches()
			&& !isNameHeader(cell)
			&& !HEADER_LIKE_VALUE.matcher(cell).matches();
	}

	private static boolean isNameHeader(String cell) {
		return NAME_COLUMN_HEADERS.contains(cell) || cell.endsWith("이름") || cell.endsWith("성명");
	}

	private static List<Integer> findNameHeaderColumns(String[] cells) {
		List<Integer> columns = new ArrayList<>();
		for (int i = 0; i < cells.length; i++) {
			if (isNameHeader(cells[i].trim())) {
				columns.add(i);
			}
		}
		return columns;
	}

	/**
	 * Luhn 체크섬 검증. 모든 실제 카드번호가 통과하는 표준 검증으로,
	 * 자릿수와 번호대가 우연히 겹친 일반 숫자열 대부분을 걸러냅니다.
	 */
	private static boolean passesLuhn(String value) {
		int sum = 0;
		boolean doubleDigit = false;
		for (int i = value.length() - 1; i >= 0; i--) {
			int digit = value.charAt(i) - '0';
			if (doubleDigit) {
				digit *= 2;
				if (digit > 9) {
					digit -= 9;
				}
			}
			sum += digit;
			doubleDigit = !doubleDigit;
		}
		return sum % 10 == 0;
	}

	/** 매칭 바로 앞 문맥이 제품코드류인지 확인합니다 ("제품 코드 A12345678" 오탐 방지) */
	private static boolean isProductCodeContext(String text, int start) {
		String before = text.substring(Math.max(0, start - PASSPORT_CONTEXT_WINDOW), start);
		return before.contains("제품") || before.contains("코드")
			|| before.contains("품번") || before.contains("모델");
	}

	private static boolean isSqlIdentifier(String value) {
		String[] parts = value.split("\\.");
		if (parts.length != 2) {
			return false;
		}
		for (String part : parts) {
			if (part.isBlank() || SQL_RESERVED_WORDS.contains(part.toUpperCase())) {
				return false;
			}
		}
		String lastPart = parts[parts.length - 1].toUpperCase();
		if ("NEXTVAL".equals(lastPart) || "CURRVAL".equals(lastPart)) {
			return false;
		}
		return true;
	}

	private static boolean hasSqlContext(String text, int start) {
		String window = text.substring(Math.max(0, start - 80), Math.min(text.length(), start + 80))
			.toUpperCase();
		return window.contains("SELECT ") || window.contains(" FROM ") || window.contains("\nFROM ")
			|| window.contains(" JOIN ") || window.contains("\nJOIN ") || window.contains(" WHERE ")
			|| window.contains("\nWHERE ") || window.contains(" UPDATE ") || window.contains("\nUPDATE ");
	}
}
