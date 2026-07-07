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
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 원본 엑셀의 "카피" 위에 AI의 편집 지시를 적용합니다.
 *
 * <p>입력 바이트를 새 워크북으로 열어 수정하므로 원본 파일은 절대 변경되지 않습니다.
 * 셀 스타일 객체를 그대로 옮겨 붙이는 방식이라, 지시가 건드리지 않은
 * 서식(색상, 글꼴, 테두리, 열 너비, 행 높이 등)은 결과 파일에 그대로 보존됩니다.
 *
 * <p>서식을 깨뜨릴 위험이 낮은 6개 연산을 지원합니다:
 * delete_column / rename_column / filter_rows / sort / replace_value / add_row.
 * 결과 신뢰를 지키기 위해, 안전하게 처리할 수 없는 파일은 어설프게 수정하지 않고
 * {@link UnsupportedEditException}으로 거절합니다. (셀 위치를 옮기는 연산 + 수식·병합 셀 파일)
 */
@Component
public class ExcelEditExecutor {

	/** 데이터가 시작되는 행 인덱스 (0행은 헤더로 간주) */
	private static final int FIRST_DATA_ROW = 1;

	/**
	 * 기존 셀의 "위치"를 옮기지 않는 연산들. (값 변경 또는 마지막 행 아래 추가)
	 * 수식 참조나 병합 구조를 깨뜨릴 수 없으므로, 구조 이동 연산과 달리
	 * 수식·병합 셀이 있는 파일에도 안전하게 적용할 수 있습니다.
	 */
	private static final Set<String> NON_STRUCTURAL_OPS = Set.of("rename_column", "replace_value", "add_row");

	private final DataFormatter formatter = new DataFormatter();

	/** 지원할 수 없는 파일·지시를 만났을 때 사용자에게 보여줄 이유를 담는 예외입니다. */
	public static class UnsupportedEditException extends RuntimeException {
		public UnsupportedEditException(String reason) {
			super(reason);
		}
	}

	/**
	 * 원본 바이트의 카피에 편집 지시를 순서대로 적용한 결과 바이트를 반환합니다.
	 * 편집은 첫 번째 시트에 적용되며, 나머지 시트는 그대로 복사됩니다.
	 */
	public byte[] apply(byte[] originalBytes, ExcelEditInstruction instruction) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(originalBytes));
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet sheet = workbook.getSheetAt(0);
			validateEditable(sheet, instruction);

			for (Op op : instruction.ops()) {
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
	private void validateEditable(Sheet sheet, ExcelEditInstruction instruction) {
		if (instruction.ops() == null || instruction.ops().isEmpty()) {
			throw new UnsupportedEditException("적용할 편집 내용이 없습니다");
		}

		// 셀 위치를 옮기지 않는 연산(이름 변경, 값 치환, 행 추가)은 수식·병합을
		// 깨뜨릴 수 없으므로 파일 상태와 무관하게 허용한다. (실사용 수정 요청의 대부분)
		boolean nonStructural = instruction.ops().stream()
			.allMatch(op -> op != null && NON_STRUCTURAL_OPS.contains(op.op()));
		if (nonStructural) {
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
			default -> throw new UnsupportedEditException("지원하지 않는 편집 지시입니다: " + op.op());
		}
	}

	/** 헤더(첫 행)에서 컬럼 이름으로 열 인덱스를 찾습니다. */
	private int requireColumn(Sheet sheet, String columnName) {
		if (columnName == null || columnName.isBlank()) {
			throw new UnsupportedEditException("편집 지시에 컬럼 이름이 없습니다");
		}
		Row header = sheet.getRow(0);
		if (header != null) {
			for (Cell cell : header) {
				if (columnName.trim().equals(formatter.formatCellValue(cell).trim())) {
					return cell.getColumnIndex();
				}
			}
		}
		throw new UnsupportedEditException("'" + columnName + "' 컬럼을 찾지 못했습니다");
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
		for (int r = sheet.getLastRowNum(); r >= FIRST_DATA_ROW; r--) {
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
	 * 행 추가: 마지막 데이터 행 아래에 새 행을 만듭니다.
	 * 바로 위 행(마지막 데이터 행)의 셀 서식·행 높이를 그대로 입혀서,
	 * 사용자가 꾸며놓은 표에 "원래 있던 행처럼" 자연스럽게 이어지게 합니다.
	 * (행 추가 요청이 파일 재생성으로 빠져 디자인이 사라지는 문제의 해결책)
	 */
	private void addRow(Sheet sheet, Op op) {
		if (op.values() == null || op.values().isEmpty()) {
			throw new UnsupportedEditException("행 추가에는 values(셀 값 목록)가 필요합니다");
		}

		// 서식 본보기는 바로 위 행. add_row를 연속 사용하면 직전에 추가된 행이 본보기가 된다.
		Row template = sheet.getRow(sheet.getLastRowNum());
		Row row = sheet.createRow(sheet.getLastRowNum() + 1);
		if (template != null) {
			row.setHeight(template.getHeight());
		}

		for (int c = 0; c < op.values().size(); c++) {
			Cell cell = row.createCell(c);
			Cell templateCell = template == null ? null : template.getCell(c);
			if (templateCell != null) {
				cell.setCellStyle(templateCell.getCellStyle());
			}

			String value = op.values().get(c) == null ? "" : op.values().get(c).trim();
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
		int lastRow = sheet.getLastRowNum();
		if (lastRow < FIRST_DATA_ROW) {
			return;
		}

		List<CapturedRow> captured = new ArrayList<>();
		for (int r = FIRST_DATA_ROW; r <= lastRow; r++) {
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
			writeRow(sheet, FIRST_DATA_ROW + i, captured.get(i));
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
