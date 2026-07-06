package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.domain.fileasset.dto.ExcelEditInstruction;
import haitai.safemask.domain.fileasset.dto.ExcelEditInstruction.Op;
import haitai.safemask.domain.fileasset.service.ExcelEditExecutor.UnsupportedEditException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExcelEditExecutor 단위 테스트입니다.
 * 편집 결과의 값과 함께, "안 건드린 서식이 보존되는가"와
 * "위험한 파일은 거절하는가"를 중점 검증합니다.
 */
class ExcelEditExecutorTest {

	private final ExcelEditExecutor executor = new ExcelEditExecutor();

	@Test
	@DisplayName("열 삭제 시 오른쪽 열이 당겨지고 값·서식이 유지된다")
	void deleteColumn() throws IOException {
		// 헤더: 이름 | 주민등록번호 | 이메일(노란 배경) — 주민등록번호 열을 삭제
		byte[] original = buildWorkbook(false, false);

		byte[] edited = executor.apply(original, instruction(
			new Op("delete_column", "주민등록번호", null, null, null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("이메일");
			assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("b@ex.com");
			// 이메일 데이터 셀의 노란 배경이 당겨진 위치에서도 유지돼야 한다
			assertThat(sheet.getRow(1).getCell(1).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.YELLOW.getIndex());
		}
	}

	@Test
	@DisplayName("컬럼 이름을 변경한다")
	void renameColumn() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("rename_column", null, "이름", "성명", null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("성명");
		}
	}

	@Test
	@DisplayName("filter_rows는 조건에 맞는 행만 남긴다")
	void filterRows() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("filter_rows", "이름", null, null, "김", null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getLastRowNum()).isEqualTo(1);
			assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("김민준");
		}
	}

	@Test
	@DisplayName("sort는 지정 컬럼 기준으로 데이터 행을 정렬하고 행별 서식을 함께 옮긴다")
	void sortRows() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("sort", "이름", null, null, null, null, null, "asc")));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("김민준");
			assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("박지훈");
			// 박지훈 행 이메일 셀의 노란 배경이 정렬 후에도 따라와야 한다
			assertThat(sheet.getRow(2).getCell(2).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.YELLOW.getIndex());
		}
	}

	@Test
	@DisplayName("수식이 포함된 파일은 수정하지 않고 이유를 밝히며 거절한다")
	void rejectFormulaFile() throws IOException {
		byte[] withFormula = buildWorkbook(true, false);

		assertThatThrownBy(() -> executor.apply(withFormula, instruction(
			new Op("delete_column", "이름", null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("수식");
	}

	@Test
	@DisplayName("병합 셀이 있는 파일은 열/행 이동 편집을 거절하지만 이름 변경은 허용한다")
	void mergedCellsPolicy() throws IOException {
		byte[] withMerge = buildWorkbook(false, true);

		assertThatThrownBy(() -> executor.apply(withMerge, instruction(
			new Op("delete_column", "이름", null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("병합");

		byte[] renamed = executor.apply(withMerge, instruction(
			new Op("rename_column", null, "이름", "성명", null, null, null, null)));
		try (XSSFWorkbook workbook = open(renamed)) {
			assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("성명");
		}
	}

	@Test
	@DisplayName("존재하지 않는 컬럼을 지정하면 이유를 밝히며 거절한다")
	void rejectUnknownColumn() throws IOException {
		assertThatThrownBy(() -> executor.apply(buildWorkbook(false, false), instruction(
			new Op("delete_column", "없는컬럼", null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("없는컬럼");
	}

	// ==== 테스트 데이터 ====

	/**
	 * 3열(이름/주민등록번호/이메일) x 데이터 2행 워크북.
	 * 이메일 데이터 셀에는 노란 배경(서식 보존 검증용)을 입힌다.
	 * 이름은 일부러 역순(박지훈 → 김민준)으로 넣어 정렬 테스트에 쓴다.
	 */
	private byte[] buildWorkbook(boolean withFormula, boolean withMerge) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet sheet = workbook.createSheet("명단");
			CellStyle yellow = workbook.createCellStyle();
			yellow.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
			yellow.setFillPattern(FillPatternType.SOLID_FOREGROUND);

			writeRow(sheet, 0, null, "이름", "주민등록번호", "이메일");
			writeRow(sheet, 1, yellow, "박지훈", "880712-1000000", "b@ex.com");
			writeRow(sheet, 2, yellow, "김민준", "900101-1000000", "a@ex.com");

			if (withFormula) {
				sheet.getRow(2).createCell(3).setCellFormula("A2");
			}
			if (withMerge) {
				sheet.addMergedRegion(new CellRangeAddress(3, 3, 0, 2));
			}

			workbook.write(out);
			return out.toByteArray();
		}
	}

	/** 행을 쓰되 마지막 셀(이메일)에만 스타일을 입힌다 */
	private void writeRow(Sheet sheet, int rowIndex, CellStyle lastCellStyle, String... values) {
		Row row = sheet.createRow(rowIndex);
		for (int c = 0; c < values.length; c++) {
			Cell cell = row.createCell(c);
			cell.setCellValue(values[c]);
			if (lastCellStyle != null && c == values.length - 1) {
				cell.setCellStyle(lastCellStyle);
			}
		}
	}

	private ExcelEditInstruction instruction(Op... ops) {
		return new ExcelEditInstruction(null, List.of(ops));
	}

	private XSSFWorkbook open(byte[] bytes) throws IOException {
		return new XSSFWorkbook(new ByteArrayInputStream(bytes));
	}
}
