package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.fileasset.dto.ExcelEditInstruction;
import haitai.safemask.domain.fileasset.dto.ExcelEditInstruction.Op;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 원본 엑셀의 "카피" 위에 AI의 편집 지시를 적용합니다.
 *
 * <p>입력 바이트를 새 워크북으로 열어 수정하므로 원본 파일은 절대 변경되지 않습니다.
 * 셀 스타일 객체를 그대로 옮겨 붙이는 방식이라, 지시가 건드리지 않은
 * 서식(색상, 글꼴, 테두리, 열 너비, 행 높이 등)은 결과 파일에 그대로 보존됩니다.
 *
 * <p>서식을 깨뜨릴 위험이 낮은 연산을 지원합니다:
 * delete_column / rename_column / filter_rows / sort / replace_value / add_row와
 * 헤더·컬럼·조건부 행 강조 같은 제한된 서식 변경.
 * 결과 신뢰를 지키기 위해, 안전하게 처리할 수 없는 파일은 어설프게 수정하지 않고
 * {@link UnsupportedEditException}으로 거절합니다. (셀 위치를 옮기는 연산 + 수식·병합 셀 파일)
 */
@Component
public class ExcelEditExecutor {

	/**
	 * 기존 셀의 "위치"를 옮기지 않는 연산들. (값 변경 또는 마지막 행 아래 추가)
	 * 수식 참조나 병합 구조를 깨뜨릴 수 없으므로, 구조 이동 연산과 달리
	 * 수식·병합 셀이 있는 파일에도 안전하게 적용할 수 있습니다.
	 */
	private static final Set<String> NON_STRUCTURAL_OPS = Set.of(
		"rename_column", "replace_value", "add_row",
		"format_header", "format_column", "highlight_rows", "set_column_width");

	private static final Set<String> SUMMARY_LABELS = Set.of("합계", "총계", "계", "total", "sum");

	private static final Map<String, String> NAMED_COLORS = Map.ofEntries(
		Map.entry("검정", "000000"),
		Map.entry("흰색", "FFFFFF"),
		Map.entry("빨강", "C00000"),
		Map.entry("붉은색", "C00000"),
		Map.entry("노랑", "FFF2CC"),
		Map.entry("노란색", "FFF2CC"),
		Map.entry("초록", "00B050"),
		Map.entry("파랑", "0070C0"),
		Map.entry("하늘색", "D9EAF7"),
		Map.entry("회색", "D9E1F2"),
		Map.entry("연회색", "E7E6E6"),
		Map.entry("주황", "F4B183")
	);

	private final DataFormatter formatter = new DataFormatter();

	/** 지원할 수 없는 파일·지시를 만났을 때 사용자에게 보여줄 이유를 담는 예외입니다. */
	public static class UnsupportedEditException extends RuntimeException {
		public UnsupportedEditException(String reason) {
			super(reason);
		}
	}

	/**
	 * 원본 바이트의 카피에 편집 지시를 순서대로 적용한 결과 바이트를 반환합니다.
	 * 편집은 지시별 sheet 값 또는 헤더 추론으로 고른 시트에 적용되며,
	 * 나머지 시트는 그대로 복사됩니다.
	 */
	public byte[] apply(byte[] originalBytes, ExcelEditInstruction instruction) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(originalBytes));
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			validateInstruction(instruction);

			for (Op op : instruction.ops()) {
				Sheet sheet = resolveSheet(workbook, op);
				validateEditable(sheet, op);
				applyOp(sheet, op);
			}

			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
		}
	}

	/**
	 * 안전하게 편집할 수 없는 조건을 미리 확인합니다.
	 * 어설프게 수정해서 깨진 파일을 주는 것보다 이유를 밝히고 거절하는 편이
	 * 서비스 신뢰에 낫다는 방침입니다. (거절 사유는 채팅 안내 문구로 노출됨)
	 */
	private void validateInstruction(ExcelEditInstruction instruction) {
		if (instruction.ops() == null || instruction.ops().isEmpty()) {
			throw new UnsupportedEditException("적용할 편집 내용이 없습니다");
		}
	}

	private void validateEditable(Sheet sheet, Op op) {
		// 셀 위치를 옮기지 않는 연산(이름 변경, 값 치환, 행 추가)은 수식·병합을
		// 깨뜨릴 수 없으므로 파일 상태와 무관하게 허용한다. (실사용 수정 요청의 대부분)
		if (op != null && NON_STRUCTURAL_OPS.contains(op.op())) {
			return;
		}

		// 열 삭제·행 이동은 수식의 셀 참조를 깨뜨린다(#REF!). 값만 있는 파일만 지원.
		for (Row row : sheet) {
			for (Cell cell : row) {
				if (cell.getCellType() == CellType.FORMULA) {
					throw new UnsupportedEditException(
						"수식이 포함된 파일은 열 삭제·행 이동을 자동 수정할 수 없습니다. 새 파일로 정리를 요청해 주세요");
				}
			}
		}

		// 병합 셀 위로 열/행을 이동시키면 병합 구조가 깨진다.
		if (sheet.getNumMergedRegions() > 0) {
			throw new UnsupportedEditException(
				"병합된 셀이 있는 파일은 열 삭제·행 이동을 자동 수정할 수 없습니다. 새 파일로 정리를 요청해 주세요");
		}
	}

	private void applyOp(Sheet sheet, Op op) {
		if (op == null || op.op() == null) {
			throw new UnsupportedEditException("알 수 없는 편집 지시가 있습니다");
		}
		switch (op.op()) {
			case "delete_column" -> deleteColumn(sheet, requireColumn(sheet, op.column()));
			case "rename_column" -> renameColumn(sheet, op);
			case "filter_rows" -> filterRows(sheet, op);
			case "sort" -> sortRows(sheet, op);
			case "replace_value" -> replaceValue(sheet, op);
			case "add_row" -> addRow(sheet, op);
			case "format_header" -> formatHeader(sheet, op);
			case "format_column" -> formatColumn(sheet, op);
			case "highlight_rows" -> highlightRows(sheet, op);
			case "set_column_width" -> setColumnWidth(sheet, op);
			default -> throw new UnsupportedEditException("지원하지 않는 편집 지시입니다: " + op.op());
		}
	}

	private Sheet resolveSheet(XSSFWorkbook workbook, Op op) {
		if (op == null) {
			throw new UnsupportedEditException("알 수 없는 편집 지시가 있습니다");
		}
		if (op.sheet() != null && !op.sheet().isBlank()) {
			return requireSheet(workbook, op.sheet());
		}

		List<String> requiredHeaders = requiredHeaders(op);
		if (!requiredHeaders.isEmpty()) {
			List<Sheet> candidates = new ArrayList<>();
			for (Sheet sheet : workbook) {
				if (requiredHeaders.stream().allMatch(header -> findColumn(sheet, header) != null)) {
					candidates.add(sheet);
				}
			}
			if (candidates.size() == 1) {
				return candidates.get(0);
			}
			if (candidates.size() > 1) {
				throw new UnsupportedEditException(
					"여러 시트에서 같은 컬럼을 찾았습니다. 수정할 sheet를 지정해 주세요: " + sheetNames(candidates));
			}
		}

		if (workbook.getNumberOfSheets() == 1) {
			return workbook.getSheetAt(0);
		}
		throw new UnsupportedEditException("여러 시트가 있는 파일은 수정할 sheet를 지정해야 합니다");
	}

	private Sheet requireSheet(XSSFWorkbook workbook, String requestedName) {
		String normalizedRequest = normalizeSheetName(requestedName);
		for (Sheet sheet : workbook) {
			if (sheet.getSheetName().equals(requestedName.trim())
				|| normalizeSheetName(sheet.getSheetName()).equals(normalizedRequest)) {
				return sheet;
			}
		}
		throw new UnsupportedEditException("시트를 찾지 못했습니다: " + requestedName);
	}

	private String normalizeSheetName(String name) {
		return name.replaceAll("[\\s_\\-·]", "").toLowerCase(Locale.ROOT);
	}

	private List<String> requiredHeaders(Op op) {
		return switch (op.op()) {
			case "delete_column", "filter_rows", "sort", "format_column", "highlight_rows", "set_column_width" ->
				op.column() == null || op.column().isBlank() ? List.of() : List.of(op.column());
			case "rename_column" -> op.from() == null || op.from().isBlank() ? List.of() : List.of(op.from());
			case "replace_value" -> op.column() == null || op.column().isBlank() ? List.of() : List.of(op.column());
			default -> List.of();
		};
	}

	private String sheetNames(List<Sheet> sheets) {
		return sheets.stream().map(Sheet::getSheetName).toList().toString();
	}

	/** 탐지된 헤더 행에서 컬럼 이름으로 열 인덱스를 찾습니다. */
	private int requireColumn(Sheet sheet, String columnName) {
		Integer columnIndex = findColumn(sheet, columnName);
		if (columnIndex != null) {
			return columnIndex;
		}
		throw new UnsupportedEditException("'" + columnName + "' 컬럼을 찾지 못했습니다");
	}

	private Integer findColumn(Sheet sheet, String columnName) {
		if (columnName == null || columnName.isBlank()) {
			return null;
		}
		int searchEnd = Math.min(sheet.getLastRowNum(), 30);
		for (int r = 0; r <= searchEnd; r++) {
			Row header = sheet.getRow(r);
			if (header == null) {
				continue;
			}
			for (Cell cell : header) {
				if (columnName.trim().equals(formatter.formatCellValue(cell).trim())) {
					return cell.getColumnIndex();
				}
			}
		}
		return null;
	}

	private Row requireHeaderRow(Sheet sheet) {
		Row header = findHeaderRow(sheet);
		if (header == null) {
			throw new UnsupportedEditException("헤더 행을 찾지 못했습니다: " + sheet.getSheetName());
		}
		return header;
	}

	private Row findHeaderRow(Sheet sheet) {
		int searchEnd = Math.min(sheet.getLastRowNum(), 30);
		Row best = null;
		int bestCount = 0;
		for (int r = 0; r <= searchEnd; r++) {
			Row row = sheet.getRow(r);
			int count = nonBlankCellCount(row);
			if (count > bestCount) {
				best = row;
				bestCount = count;
			}
		}
		return bestCount >= 2 ? best : sheet.getRow(0);
	}

	private int nonBlankCellCount(Row row) {
		if (row == null) {
			return 0;
		}
		int count = 0;
		for (Cell cell : row) {
			if (cell.getCellType() == CellType.FORMULA) {
				continue;
			}
			if (!formatter.formatCellValue(cell).trim().isEmpty()) {
				count++;
			}
		}
		return count;
	}

	private int firstDataRow(Sheet sheet) {
		return requireHeaderRow(sheet).getRowNum() + 1;
	}

	private int lastContentRow(Sheet sheet) {
		for (int r = sheet.getLastRowNum(); r >= 0; r--) {
			Row row = sheet.getRow(r);
			if (rowHasContent(row)) {
				return r;
			}
		}
		return 0;
	}

	private boolean rowHasContent(Row row) {
		if (row == null) {
			return false;
		}
		for (Cell cell : row) {
			if (!formatter.formatCellValue(cell).trim().isEmpty() || cell.getCellType() == CellType.FORMULA) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 열을 삭제합니다. POI에는 열 삭제 API가 없어, 오른쪽 셀들을
	 * 값·스타일과 함께 한 칸씩 왼쪽으로 옮기고 마지막 셀을 제거하는 방식으로 구현합니다.
	 * 열 너비도 함께 왼쪽으로 당겨 시각적 배치를 유지합니다.
	 */
	private void deleteColumn(Sheet sheet, int columnIndex) {
		int maxCellCount = 0;
		for (int r = 0; r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			maxCellCount = Math.max(maxCellCount, row.getLastCellNum());

			for (int c = columnIndex; c < row.getLastCellNum() - 1; c++) {
				Cell source = row.getCell(c + 1);
				Cell target = row.getCell(c);
				if (target != null) {
					row.removeCell(target);
				}
				if (source != null) {
					copyCell(source, row.createCell(c));
				}
			}
			Cell last = row.getCell(row.getLastCellNum() - 1);
			if (last != null && last.getColumnIndex() >= columnIndex) {
				row.removeCell(last);
			}
		}

		for (int c = columnIndex; c < maxCellCount - 1; c++) {
			sheet.setColumnWidth(c, sheet.getColumnWidth(c + 1));
		}
	}

	private void renameColumn(Sheet sheet, Op op) {
		if (op.to() == null || op.to().isBlank()) {
			throw new UnsupportedEditException("바꿀 컬럼 이름이 없습니다");
		}
		int columnIndex = requireColumn(sheet, op.from());
		sheet.getRow(0).getCell(columnIndex).setCellValue(op.to().trim());
	}

	/**
	 * 조건에 맞는 데이터 행만 남깁니다.
	 * 서버가 원본의 실제 값으로 비교하므로, AI가 마스킹 토큰만 보고 만든
	 * 조건(원복돼서 들어옴)도 정확하게 동작합니다.
	 */
	private void filterRows(Sheet sheet, Op op) {
		int columnIndex = requireColumn(sheet, op.column());
		validateFilterCondition(op);

		// 아래에서 위로 지워야 shiftRows로 당겨진 행 인덱스가 꼬이지 않음
		for (int r = lastContentRow(sheet); r >= firstDataRow(sheet); r--) {
			Row row = sheet.getRow(r);
			String value = row == null ? "" : formatter.formatCellValue(row.getCell(columnIndex)).trim();
			if (!matchesFilter(value, op)) {
				removeRow(sheet, r);
			}
		}
	}

	private void validateFilterCondition(Op op) {
		int conditions = 0;
		conditions += op.contains() != null ? 1 : 0;
		conditions += op.notContains() != null ? 1 : 0;
		conditions += op.equals() != null ? 1 : 0;
		if (conditions != 1) {
			throw new UnsupportedEditException("행 필터 조건은 contains/notContains/equals 중 하나여야 합니다");
		}
	}

	private boolean matchesFilter(String value, Op op) {
		if (op.contains() != null) {
			return value.contains(op.contains().trim());
		}
		if (op.notContains() != null) {
			return !value.contains(op.notContains().trim());
		}
		return value.equals(op.equals().trim());
	}

	/**
	 * 셀 값 치환: 텍스트에 from이 포함된 셀을 찾아 그 부분만 to로 바꿉니다.
	 * 기존 셀 객체에 값만 다시 쓰므로 스타일·위치가 완전히 보존됩니다.
	 * (셀 값 변경·오타 수정 요청이 파일 재생성으로 빠져 서식이 사라지는 문제의 해결책)
	 *
	 * <p>column이 지정되면 해당 컬럼만, 없으면 시트 전체를 대상으로 합니다.
	 * 서버가 원본의 실제 값으로 실행하므로 마스킹 토큰 조건도 정확히 동작합니다.
	 */
	private void replaceValue(Sheet sheet, Op op) {
		if (op.from() == null || op.from().isBlank() || op.to() == null) {
			throw new UnsupportedEditException("값 치환에는 from(찾을 값)과 to(바꿀 값)가 모두 필요합니다");
		}
		Integer columnIndex = op.column() == null || op.column().isBlank()
			? null : requireColumn(sheet, op.column());
		String from = op.from().trim();
		String to = op.to().trim();

		int replacedCount = 0;
		for (Row row : sheet) {
			for (Cell cell : row) {
				if (columnIndex != null && cell.getColumnIndex() != columnIndex) {
					continue;
				}
				// 수식 셀의 "표시값"을 문자열로 덮으면 수식이 사라지므로 건드리지 않는다
				if (cell.getCellType() == CellType.FORMULA) {
					continue;
				}
				String text = formatter.formatCellValue(cell);
				if (!text.contains(from)) {
					continue;
				}
				writeReplacedValue(cell, text.replace(from, to));
				replacedCount++;
			}
		}

		// 못 찾았는데 조용히 성공하면 사용자가 "바뀐 파일"로 오해한다. 이유를 밝히고 거절.
		if (replacedCount == 0) {
			throw new UnsupportedEditException("바꿀 값 '" + from + "'을(를) 파일에서 찾지 못했습니다");
		}
	}

	/**
	 * 행 추가: 표 본문 스타일을 새 행에 입힙니다.
	 * 마지막 행이 합계/총계처럼 보이면 그 위에 삽입해 합계 행 디자인이 본문으로
	 * 번지지 않게 하고, 값이 비어 있는 열도 헤더 범위만큼 셀을 만들어 테두리·포맷을 이어줍니다.
	 */
	private void addRow(Sheet sheet, Op op) {
		if (op.values() == null || op.values().isEmpty()) {
			throw new UnsupportedEditException("행 추가에는 values(셀 값 목록)가 필요합니다");
		}

		int insertIndex = resolveRowInsertIndex(sheet);
		Row template = resolveBodyTemplateRow(sheet, insertIndex);
		if (insertIndex <= lastContentRow(sheet)) {
			sheet.shiftRows(insertIndex, sheet.getLastRowNum(), 1, true, false);
		}

		Row row = sheet.createRow(insertIndex);
		if (template != null) {
			row.setHeight(template.getHeight());
		}

		int maxColumn = Math.max(op.values().size(), headerColumnCount(sheet));
		for (int c = 0; c < maxColumn; c++) {
			Cell cell = row.createCell(c);
			Cell templateCell = template == null ? null : template.getCell(c);
			if (templateCell != null) {
				cell.setCellStyle(templateCell.getCellStyle());
			}

			String value = c < op.values().size() && op.values().get(c) != null ? op.values().get(c).trim() : "";
			// 본보기 셀이 숫자면 새 값도 숫자로 저장해 정렬·계산 호환을 지킨다
			if (templateCell != null && templateCell.getCellType() == CellType.NUMERIC) {
				Double number = tryParseNumber(value);
				if (number != null) {
					cell.setCellValue(number);
					continue;
				}
			}
			cell.setCellValue(value);
		}
		expandTablesForInsertedRow(sheet, insertIndex);
	}

	private void expandTablesForInsertedRow(Sheet sheet, int insertIndex) {
		if (!(sheet instanceof XSSFSheet xssfSheet)) {
			return;
		}
		for (XSSFTable table : xssfSheet.getTables()) {
			CellReference start = table.getStartCellReference();
			CellReference end = table.getEndCellReference();
			if (start == null || end == null) {
				continue;
			}
			if (insertIndex < start.getRow() + 1 || insertIndex > end.getRow() + 1) {
				continue;
			}
			CellReference expandedEnd = new CellReference(Math.max(insertIndex, end.getRow() + 1), end.getCol());
			table.setArea(new AreaReference(start, expandedEnd, SpreadsheetVersion.EXCEL2007));
		}
	}

	private int resolveRowInsertIndex(Sheet sheet) {
		int lastRow = lastContentRow(sheet);
		Row last = sheet.getRow(lastRow);
		if (last != null && isSummaryRow(last)) {
			return lastRow;
		}
		return lastRow + 1;
	}

	private Row resolveBodyTemplateRow(Sheet sheet, int insertIndex) {
		Row alternatingTemplate = resolveAlternatingTemplateRow(sheet, insertIndex);
		if (alternatingTemplate != null) {
			return alternatingTemplate;
		}
		for (int r = insertIndex - 1; r >= firstDataRow(sheet); r--) {
			Row row = sheet.getRow(r);
			if (row != null && !isSummaryRow(row)) {
				return row;
			}
		}
		return sheet.getRow(Math.max(0, insertIndex - 1));
	}

	private Row resolveAlternatingTemplateRow(Sheet sheet, int insertIndex) {
		List<Row> previousRows = new ArrayList<>();
		for (int r = insertIndex - 1; r >= firstDataRow(sheet) && previousRows.size() < 4; r--) {
			Row row = sheet.getRow(r);
			if (row != null && rowHasContent(row) && !isSummaryRow(row)) {
				previousRows.add(row);
			}
		}
		if (previousRows.size() < 4) {
			return null;
		}

		int maxColumn = headerColumnCount(sheet);
		String previous = styleSignature(previousRows.get(0), maxColumn);
		String beforePrevious = styleSignature(previousRows.get(1), maxColumn);
		String third = styleSignature(previousRows.get(2), maxColumn);
		String fourth = styleSignature(previousRows.get(3), maxColumn);
		if (!previous.equals(beforePrevious) && previous.equals(third) && beforePrevious.equals(fourth)) {
			return previousRows.get(1);
		}
		return null;
	}

	private String styleSignature(Row row, int maxColumn) {
		StringBuilder signature = new StringBuilder();
		signature.append(row.getHeight()).append('|');
		for (int c = 0; c < maxColumn; c++) {
			Cell cell = row.getCell(c);
			if (cell == null) {
				signature.append("null;");
				continue;
			}
			CellStyle style = cell.getCellStyle();
			signature.append(style.getFillPattern()).append(',')
				.append(style.getFillForegroundColor()).append(',')
				.append(style.getBorderTop()).append(',')
				.append(style.getBorderRight()).append(',')
				.append(style.getBorderBottom()).append(',')
				.append(style.getBorderLeft()).append(',')
				.append(style.getDataFormat()).append(',')
				.append(style.getFontIndexAsInt()).append(',')
				.append(style.getAlignment()).append(',')
				.append(style.getVerticalAlignment()).append(',')
				.append(style.getWrapText()).append(';');
		}
		return signature.toString();
	}

	private boolean isSummaryRow(Row row) {
		for (Cell cell : row) {
			if (cell.getCellType() == CellType.FORMULA) {
				return true;
			}
		}
		String first = formatter.formatCellValue(row.getCell(0)).trim().toLowerCase(Locale.ROOT);
		return SUMMARY_LABELS.contains(first);
	}

	private int headerColumnCount(Sheet sheet) {
		Row header = requireHeaderRow(sheet);
		return header == null || header.getLastCellNum() < 0 ? 0 : header.getLastCellNum();
	}

	private void formatHeader(Sheet sheet, Op op) {
		Row header = requireHeaderRow(sheet);
		for (Cell cell : header) {
			applyStyle(cell, op, true);
		}
	}

	private void formatColumn(Sheet sheet, Op op) {
		int columnIndex = requireColumn(sheet, op.column());
		if (op.width() != null) {
			applyColumnWidth(sheet, columnIndex, op.width());
		}
		for (int r = firstDataRow(sheet); r <= lastContentRow(sheet); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			Cell cell = row.getCell(columnIndex);
			if (cell != null) {
				applyStyle(cell, op, false);
			}
		}
	}

	private void highlightRows(Sheet sheet, Op op) {
		int columnIndex = requireColumn(sheet, op.column());
		validateFilterCondition(op);
		for (int r = firstDataRow(sheet); r <= lastContentRow(sheet); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			String value = formatter.formatCellValue(row.getCell(columnIndex)).trim();
			if (!matchesFilter(value, op)) {
				continue;
			}
			for (Cell cell : row) {
				applyStyle(cell, op, false);
			}
		}
	}

	private void setColumnWidth(Sheet sheet, Op op) {
		int columnIndex = requireColumn(sheet, op.column());
		if (op.width() == null) {
			throw new UnsupportedEditException("열 너비 조정에는 width가 필요합니다");
		}
		applyColumnWidth(sheet, columnIndex, op.width());
	}

	private void applyColumnWidth(Sheet sheet, int columnIndex, double width) {
		if (width <= 0 || width > 100) {
			throw new UnsupportedEditException("열 너비는 0보다 크고 100 이하여야 합니다");
		}
		sheet.setColumnWidth(columnIndex, (int) Math.round(width * 256));
	}

	private void applyStyle(Cell cell, Op op, boolean defaultHeaderBold) {
		XSSFWorkbook workbook = (XSSFWorkbook) cell.getSheet().getWorkbook();
		XSSFCellStyle style = workbook.createCellStyle();
		style.cloneStyleFrom(cell.getCellStyle());

		if (op.backgroundColor() != null && !op.backgroundColor().isBlank()) {
			style.setFillForegroundColor(toColor(op.backgroundColor().trim()));
			style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		}
		if (Boolean.TRUE.equals(op.border())) {
			style.setBorderTop(BorderStyle.THIN);
			style.setBorderRight(BorderStyle.THIN);
			style.setBorderBottom(BorderStyle.THIN);
			style.setBorderLeft(BorderStyle.THIN);
		}
		if (op.numberFormat() != null && !op.numberFormat().isBlank()) {
			style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat(op.numberFormat().trim()));
		}

		Boolean bold = op.bold() != null ? op.bold() : (defaultHeaderBold ? Boolean.TRUE : null);
		if (bold != null || (op.fontColor() != null && !op.fontColor().isBlank())) {
			style.setFont(copyFont(workbook, cell.getCellStyle(), bold, op.fontColor()));
		}
		cell.setCellStyle(style);
	}

	private XSSFFont copyFont(XSSFWorkbook workbook, CellStyle baseStyle, Boolean bold, String fontColor) {
		Font base = workbook.getFontAt(baseStyle.getFontIndexAsInt());
		XSSFFont font = workbook.createFont();
		font.setFontName(base.getFontName());
		font.setFontHeight(base.getFontHeight());
		font.setItalic(base.getItalic());
		font.setStrikeout(base.getStrikeout());
		font.setTypeOffset(base.getTypeOffset());
		font.setUnderline(base.getUnderline());
		font.setCharSet(base.getCharSet());
		font.setBold(bold != null ? bold : base.getBold());
		if (fontColor != null && !fontColor.isBlank()) {
			font.setColor(toColor(fontColor.trim()));
		} else {
			font.setColor(base.getColor());
		}
		return font;
	}

	private XSSFColor toColor(String raw) {
		String normalized = raw.startsWith("#") ? raw.substring(1) : raw;
		normalized = NAMED_COLORS.getOrDefault(normalized.toLowerCase(Locale.ROOT), normalized);
		if (!normalized.matches("[0-9a-fA-F]{6}")) {
			throw new UnsupportedEditException("지원하지 않는 색상 값입니다: " + raw);
		}
		byte[] rgb = new byte[] {
			(byte) Integer.parseInt(normalized.substring(0, 2), 16),
			(byte) Integer.parseInt(normalized.substring(2, 4), 16),
			(byte) Integer.parseInt(normalized.substring(4, 6), 16)
		};
		return new XSSFColor(rgb, null);
	}

	/** 숫자 셀이 숫자로 유지될 수 있으면 숫자 타입으로 다시 써서 엑셀 수식·정렬 호환을 지킵니다. */
	private void writeReplacedValue(Cell cell, String newText) {
		if (cell.getCellType() == CellType.NUMERIC) {
			Double number = tryParseNumber(newText);
			if (number != null) {
				cell.setCellValue(number);
				return;
			}
		}
		cell.setCellValue(newText);
	}

	private void removeRow(Sheet sheet, int rowIndex) {
		Row row = sheet.getRow(rowIndex);
		if (row != null) {
			sheet.removeRow(row);
		}
		if (rowIndex < sheet.getLastRowNum()) {
			sheet.shiftRows(rowIndex + 1, sheet.getLastRowNum(), -1);
		}
	}

	/**
	 * 데이터 행을 지정 컬럼 기준으로 정렬합니다.
	 * 행 내용(값·스타일·높이)을 메모리에 떠서 정렬 후 다시 쓰는 방식입니다.
	 * 스타일 객체는 워크북 소속이라 재배치해도 서식이 유지됩니다.
	 * 숫자로 해석되는 값은 숫자로, 그 외는 한국어 사전순으로 비교합니다.
	 */
	private void sortRows(Sheet sheet, Op op) {
		int columnIndex = requireColumn(sheet, op.column());
		int firstDataRow = firstDataRow(sheet);
		int lastRow = lastContentRow(sheet);
		if (lastRow < firstDataRow) {
			return;
		}

		List<CapturedRow> captured = new ArrayList<>();
		for (int r = firstDataRow; r <= lastRow; r++) {
			captured.add(captureRow(sheet.getRow(r), columnIndex));
		}

		Collator collator = Collator.getInstance(Locale.KOREAN);
		Comparator<CapturedRow> comparator = (a, b) -> {
			Double numberA = tryParseNumber(a.sortKey());
			Double numberB = tryParseNumber(b.sortKey());
			if (numberA != null && numberB != null) {
				return Double.compare(numberA, numberB);
			}
			return collator.compare(a.sortKey(), b.sortKey());
		};
		if ("desc".equalsIgnoreCase(op.order())) {
			comparator = comparator.reversed();
		}
		captured.sort(comparator);

		for (int i = 0; i < captured.size(); i++) {
			writeRow(sheet, firstDataRow + i, captured.get(i));
		}
	}

	private Double tryParseNumber(String value) {
		try {
			return Double.valueOf(value.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** 정렬을 위해 메모리에 떠 놓는 행 스냅샷 */
	private record CapturedRow(String sortKey, Short height, List<CapturedCell> cells) {
	}

	/** 셀 스냅샷. 스타일은 워크북 소속 객체 참조라 그대로 재사용해도 안전 */
	private record CapturedCell(int columnIndex, CellType type, String text, double number, boolean bool,
		CellStyle style) {
	}

	private CapturedRow captureRow(Row row, int sortColumnIndex) {
		if (row == null) {
			return new CapturedRow("", null, List.of());
		}

		List<CapturedCell> cells = new ArrayList<>();
		for (Cell cell : row) {
			cells.add(new CapturedCell(cell.getColumnIndex(), cell.getCellType(),
				cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : null,
				cell.getCellType() == CellType.NUMERIC ? cell.getNumericCellValue() : 0,
				cell.getCellType() == CellType.BOOLEAN && cell.getBooleanCellValue(),
				cell.getCellStyle()));
		}
		String sortKey = formatter.formatCellValue(row.getCell(sortColumnIndex)).trim();
		return new CapturedRow(sortKey, row.getHeight(), cells);
	}

	private void writeRow(Sheet sheet, int rowIndex, CapturedRow captured) {
		Row existing = sheet.getRow(rowIndex);
		if (existing != null) {
			sheet.removeRow(existing);
		}
		Row row = sheet.createRow(rowIndex);
		if (captured.height() != null) {
			row.setHeight(captured.height());
		}

		for (CapturedCell capturedCell : captured.cells()) {
			Cell cell = row.createCell(capturedCell.columnIndex());
			cell.setCellStyle(capturedCell.style());
			switch (capturedCell.type()) {
				case STRING -> cell.setCellValue(capturedCell.text());
				case NUMERIC -> cell.setCellValue(capturedCell.number());
				case BOOLEAN -> cell.setCellValue(capturedCell.bool());
				default -> cell.setBlank();
			}
		}
	}

	private void copyCell(Cell source, Cell target) {
		target.setCellStyle(source.getCellStyle());
		switch (source.getCellType()) {
			case STRING -> target.setCellValue(source.getStringCellValue());
			case NUMERIC -> target.setCellValue(source.getNumericCellValue());
			case BOOLEAN -> target.setCellValue(source.getBooleanCellValue());
			default -> target.setBlank();
		}
	}
}
