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
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
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
			new Op("delete_column", "주민등록번호", null, null, null, null, null, null, null)));

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
			new Op("rename_column", null, "이름", "성명", null, null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("성명");
		}
	}

	@Test
	@DisplayName("filter_rows는 조건에 맞는 행만 남긴다")
	void filterRows() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("filter_rows", "이름", null, null, "김", null, null, null, null)));

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
			new Op("sort", "이름", null, null, null, null, null, "asc", null)));

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
	@DisplayName("replace_value는 셀 값의 해당 부분만 바꾸고 서식을 유지한다")
	void replaceValue() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("replace_value", "이메일", "b@ex.com", "new@ex.com", null, null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("new@ex.com");
			// 값만 바뀌고 셀의 노란 배경 서식은 그대로여야 한다
			assertThat(sheet.getRow(1).getCell(2).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.YELLOW.getIndex());
			// 다른 행은 건드리지 않는다
			assertThat(sheet.getRow(2).getCell(2).getStringCellValue()).isEqualTo("a@ex.com");
		}
	}

	@Test
	@DisplayName("replace_value는 column을 생략하면 표 전체에서 치환한다")
	void replaceValueWholeSheet() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("replace_value", null, "박지훈", "박지원", null, null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("박지원");
		}
	}

	@Test
	@DisplayName("replace_value는 수식·병합 셀이 있는 파일에도 적용된다 (값만 바꾸므로 안전)")
	void replaceValueAllowsFormulaAndMerge() throws IOException {
		byte[] tricky = buildWorkbook(true, true);

		byte[] edited = executor.apply(tricky, instruction(
			new Op("replace_value", "이름", "김민준", "김민서", null, null, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("김민서");
			// 수식 셀과 병합 구조는 그대로 유지돼야 한다
			assertThat(sheet.getRow(2).getCell(3).getCellFormula()).isEqualTo("A2");
			assertThat(sheet.getNumMergedRegions()).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("바꿀 값을 찾지 못하면 조용히 성공하지 않고 이유를 밝히며 거절한다")
	void replaceValueRejectsWhenNotFound() throws IOException {
		assertThatThrownBy(() -> executor.apply(buildWorkbook(false, false), instruction(
			new Op("replace_value", null, "없는값", "새값", null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("없는값");
	}

	@Test
	@DisplayName("add_row는 마지막 행 아래에 새 행을 추가하고 기존 행의 서식을 그대로 입힌다")
	void addRow() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("이서연", "950505-2000000", "c@ex.com"))));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			Row added = sheet.getRow(3);
			assertThat(added.getCell(0).getStringCellValue()).isEqualTo("이서연");
			assertThat(added.getCell(2).getStringCellValue()).isEqualTo("c@ex.com");
			// 바로 위 행(이메일 셀 노란 배경)의 서식이 새 행에도 이어져야 한다
			assertThat(added.getCell(2).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.YELLOW.getIndex());
			// 기존 행은 그대로여야 한다
			assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("김민준");
		}
	}

	@Test
	@DisplayName("add_row를 여러 번 쓰면 순서대로 아래에 쌓인다")
	void addMultipleRows() throws IOException {
		byte[] edited = executor.apply(buildWorkbook(false, false), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("이서연", "950505-2000000", "c@ex.com")),
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("최도윤", "970707-1000000", "d@ex.com"))));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("이서연");
			assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("최도윤");
		}
	}

	@Test
	@DisplayName("add_row를 여러 번 써도 기존 줄무늬 행 색상 패턴을 이어간다")
	void addMultipleRowsPreservesAlternatingRowStyles() throws IOException {
		byte[] edited = executor.apply(buildZebraWorkbook(), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-005", "신규A", "중요")),
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-006", "신규B", "일반")),
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-007", "신규C", "중요"))));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(5).getCell(0).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
			assertThat(sheet.getRow(6).getCell(0).getCellStyle().getFillPattern())
				.isNotEqualTo(FillPatternType.SOLID_FOREGROUND);
			assertThat(sheet.getRow(7).getCell(0).getCellStyle().getFillForegroundColor())
				.isEqualTo(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
		}
	}

	@Test
	@DisplayName("add_row는 엑셀 테이블 범위를 확장해 테이블 디자인 규칙을 새 행에도 적용되게 한다")
	void addRowExtendsExcelTableRange() throws IOException {
		byte[] edited = executor.apply(buildWorkbookWithTable(), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-005", "신규A", "중요")),
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-006", "신규B", "일반"))));

		try (XSSFWorkbook workbook = open(edited)) {
			XSSFSheet sheet = workbook.getSheet("고객상담_접수대장");
			XSSFTable table = sheet.getTables().get(0);
			assertThat(table.getStartCellReference().formatAsString()).isEqualTo("A1");
			assertThat(table.getEndCellReference().formatAsString()).isEqualTo("C7");
			assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("CS-005");
			assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("CS-006");
		}
	}

	@Test
	@DisplayName("add_row에 values가 없으면 이유를 밝히며 거절한다")
	void addRowRequiresValues() throws IOException {
		assertThatThrownBy(() -> executor.apply(buildWorkbook(false, false), instruction(
			new Op("add_row", null, null, null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("values");
	}

	@Test
	@DisplayName("add_row는 합계 행 위에 본문 스타일로 새 행을 추가한다")
	void addRowBeforeSummary() throws IOException {
		byte[] edited = executor.apply(buildSalesWorkbook(), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("신제품", "3000", "중요"))));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			Row added = sheet.getRow(3);
			assertThat(added.getCell(0).getStringCellValue()).isEqualTo("신제품");
			assertThat(added.getCell(1).getNumericCellValue()).isEqualTo(3000);
			assertThat(added.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
			assertThat(added.getCell(2).getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
			assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("합계");
		}
	}

	@Test
	@DisplayName("format_header는 헤더에 배경색·글자색·굵게·테두리를 적용한다")
	void formatHeader() throws IOException {
		byte[] edited = executor.apply(buildSalesWorkbook(), instruction(
			new Op("format_header", null, null, null, null, null, null, null, null,
				"D9EAF7", "C00000", true, true, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Cell header = workbook.getSheetAt(0).getRow(0).getCell(0);
			Font font = workbook.getFontAt(header.getCellStyle().getFontIndexAsInt());
			assertThat(font.getBold()).isTrue();
			assertThat(header.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
			assertThat(header.getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
		}
	}

	@Test
	@DisplayName("format_column은 금액 컬럼에 숫자 서식과 열 너비를 적용한다")
	void formatColumn() throws IOException {
		byte[] edited = executor.apply(buildSalesWorkbook(), instruction(
			new Op("format_column", "금액", null, null, null, null, null, null, null,
				null, null, null, null, "#,##0", 18.0, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getColumnWidth(1)).isEqualTo(18 * 256);
			assertThat(sheet.getRow(1).getCell(1).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
		}
	}

	@Test
	@DisplayName("highlight_rows는 조건에 맞는 행 전체에 강조 서식을 적용한다")
	void highlightRows() throws IOException {
		byte[] edited = executor.apply(buildSalesWorkbook(), instruction(
			new Op("highlight_rows", "상태", null, null, null, null, "중요", null, null,
				"FFF2CC", "C00000", true, true, null, null, null)));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet sheet = workbook.getSheetAt(0);
			Cell highlighted = sheet.getRow(2).getCell(0);
			Cell untouched = sheet.getRow(1).getCell(0);
			assertThat(highlighted.getCellStyle().getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
			assertThat(highlighted.getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
			assertThat(untouched.getCellStyle().getFillPattern()).isNotEqualTo(FillPatternType.SOLID_FOREGROUND);
		}
	}

	@Test
	@DisplayName("다중 시트 파일은 sheet 지시로 대상 시트를 고르고 제목/설명 아래의 표에 행을 추가한다")
	void addRowToRequestedSheetWithDetectedHeader() throws IOException {
		byte[] edited = executor.apply(buildMultiSheetWorkbook(), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-003", "2026-07-06 10:00", "이서연", "중요"),
				null, null, null, null, null, null, null, "고객상담접수대장")));

		try (XSSFWorkbook workbook = open(edited)) {
			Sheet guide = workbook.getSheet("안내");
			Sheet target = workbook.getSheet("고객상담_접수대장");
			assertThat(guide.getRow(2)).isNull();

			Row added = target.getRow(6);
			assertThat(added.getCell(0).getStringCellValue()).isEqualTo("CS-003");
			assertThat(added.getCell(2).getStringCellValue()).isEqualTo("이서연");
			assertThat(added.getCell(3).getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
		}
	}

	@Test
	@DisplayName("다중 시트 파일에서 sheet를 추론할 수 없으면 첫 시트를 수정하지 않고 거절한다")
	void rejectAmbiguousMultiSheetEdit() throws IOException {
		assertThatThrownBy(() -> executor.apply(buildMultiSheetWorkbook(), instruction(
			new Op("add_row", null, null, null, null, null, null, null,
				List.of("CS-003", "2026-07-06 10:00", "이서연", "중요")))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("sheet");
	}

	@Test
	@DisplayName("수식이 포함된 파일은 수정하지 않고 이유를 밝히며 거절한다")
	void rejectFormulaFile() throws IOException {
		byte[] withFormula = buildWorkbook(true, false);

		assertThatThrownBy(() -> executor.apply(withFormula, instruction(
			new Op("delete_column", "이름", null, null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("수식");
	}

	@Test
	@DisplayName("병합 셀이 있는 파일은 열/행 이동 편집을 거절하지만 이름 변경은 허용한다")
	void mergedCellsPolicy() throws IOException {
		byte[] withMerge = buildWorkbook(false, true);

		assertThatThrownBy(() -> executor.apply(withMerge, instruction(
			new Op("delete_column", "이름", null, null, null, null, null, null, null))))
			.isInstanceOf(UnsupportedEditException.class)
			.hasMessageContaining("병합");

		byte[] renamed = executor.apply(withMerge, instruction(
			new Op("rename_column", null, "이름", "성명", null, null, null, null, null)));
		try (XSSFWorkbook workbook = open(renamed)) {
			assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("성명");
		}
	}

	@Test
	@DisplayName("존재하지 않는 컬럼을 지정하면 이유를 밝히며 거절한다")
	void rejectUnknownColumn() throws IOException {
		assertThatThrownBy(() -> executor.apply(buildWorkbook(false, false), instruction(
			new Op("delete_column", "없는컬럼", null, null, null, null, null, null, null))))
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

	private byte[] buildSalesWorkbook() throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet sheet = workbook.createSheet("매출");
			CellStyle body = workbook.createCellStyle();
			body.setBorderTop(BorderStyle.THIN);
			body.setBorderRight(BorderStyle.THIN);
			body.setBorderBottom(BorderStyle.THIN);
			body.setBorderLeft(BorderStyle.THIN);

			CellStyle money = workbook.createCellStyle();
			money.cloneStyleFrom(body);
			money.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("#,##0"));

			CellStyle summary = workbook.createCellStyle();
			summary.cloneStyleFrom(body);
			summary.setFillForegroundColor(IndexedColors.LIGHT_GREEN.getIndex());
			summary.setFillPattern(FillPatternType.SOLID_FOREGROUND);

			writeRow(sheet, 0, null, "상품", "금액", "상태");
			writeSalesRow(sheet, 1, body, money, "기존A", 1000, "일반");
			writeSalesRow(sheet, 2, body, money, "기존B", 2000, "중요");
			writeRow(sheet, 3, summary, "합계", "3000", "");

			workbook.write(out);
			return out.toByteArray();
		}
	}

	private byte[] buildZebraWorkbook() throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet sheet = workbook.createSheet("고객상담_접수대장");
			writeRow(sheet, 0, null, "접수번호", "고객명", "상태");

			CellStyle blue = workbook.createCellStyle();
			blue.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
			blue.setFillPattern(FillPatternType.SOLID_FOREGROUND);
			blue.setBorderBottom(BorderStyle.THIN);

			CellStyle white = workbook.createCellStyle();
			white.setBorderBottom(BorderStyle.THIN);

			writeStyledRow(sheet, 1, blue, "CS-001", "김민수", "일반");
			writeStyledRow(sheet, 2, white, "CS-002", "이하늘", "중요");
			writeStyledRow(sheet, 3, blue, "CS-003", "박지현", "일반");
			writeStyledRow(sheet, 4, white, "CS-004", "최준용", "중요");

			workbook.write(out);
			return out.toByteArray();
		}
	}

	private byte[] buildWorkbookWithTable() throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			XSSFSheet sheet = workbook.createSheet("고객상담_접수대장");
			writeRow(sheet, 0, null, "접수번호", "고객명", "상태");
			writeRow(sheet, 1, null, "CS-001", "김민수", "일반");
			writeRow(sheet, 2, null, "CS-002", "이하늘", "중요");
			writeRow(sheet, 3, null, "CS-003", "박지현", "일반");
			writeRow(sheet, 4, null, "CS-004", "최준용", "중요");

			AreaReference area = new AreaReference(new CellReference(0, 0), new CellReference(4, 2),
				SpreadsheetVersion.EXCEL2007);
			XSSFTable table = sheet.createTable(area);
			table.setName("CustomerLedgerTable");
			table.setDisplayName("CustomerLedgerTable");
			table.getCTTable().addNewTableStyleInfo().setName("TableStyleMedium2");
			table.getCTTable().getTableStyleInfo().setShowRowStripes(true);

			workbook.write(out);
			return out.toByteArray();
		}
	}

	private byte[] buildMultiSheetWorkbook() throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet guide = workbook.createSheet("안내");
			writeRow(guide, 0, null, "개인정보 이름 마스킹 테스트 데이터");
			writeRow(guide, 1, null, "업무 안내");

			Sheet target = workbook.createSheet("고객상담_접수대장");
			writeRow(target, 0, null, "고객상담 접수대장");
			writeRow(target, 1, null, "콜센터 접수 화면을 가정한 정형 데이터입니다.");
			writeRow(target, 3, null, "접수번호", "접수일시", "고객명", "상태");

			CellStyle body = workbook.createCellStyle();
			body.setBorderTop(BorderStyle.THIN);
			body.setBorderRight(BorderStyle.THIN);
			body.setBorderBottom(BorderStyle.THIN);
			body.setBorderLeft(BorderStyle.THIN);
			writeRow(target, 4, body, "CS-001", "2026-07-06 09:00", "김민수", "일반");
			writeRow(target, 5, body, "CS-002", "2026-07-06 09:30", "박지현", "중요");

			// 실제 파일처럼 아래쪽에 값 없는 서식 행이 있어도 데이터 끝으로 오해하면 안 된다.
			Row blankStyled = target.createRow(10);
			for (int c = 0; c < 4; c++) {
				blankStyled.createCell(c).setCellStyle(body);
			}

			workbook.write(out);
			return out.toByteArray();
		}
	}

	private void writeSalesRow(Sheet sheet, int rowIndex, CellStyle body, CellStyle money, String name, double amount,
		String status) {
		Row row = sheet.createRow(rowIndex);
		Cell nameCell = row.createCell(0);
		nameCell.setCellValue(name);
		nameCell.setCellStyle(body);
		Cell amountCell = row.createCell(1);
		amountCell.setCellValue(amount);
		amountCell.setCellStyle(money);
		Cell statusCell = row.createCell(2);
		statusCell.setCellValue(status);
		statusCell.setCellStyle(body);
	}

	private void writeStyledRow(Sheet sheet, int rowIndex, CellStyle style, String... values) {
		Row row = sheet.createRow(rowIndex);
		for (int c = 0; c < values.length; c++) {
			Cell cell = row.createCell(c);
			cell.setCellValue(values[c]);
			cell.setCellStyle(style);
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
