package haitai.safemask.domain.fileasset.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ExcelFileWriter 단위 테스트입니다.
 * 생성된 바이트를 POI로 다시 열어 셀 값이 그대로인지 확인합니다.
 */
class ExcelFileWriterTest {

	private final ExcelFileWriter writer = new ExcelFileWriter();

	@Test
	@DisplayName("행 데이터가 xlsx로 변환되고 다시 열었을 때 값이 그대로 유지된다")
	void writeAndReadBack() throws IOException {
		List<List<String>> rows = List.of(
			List.of("이름", "휴대폰"),
			List.of("김민준", "010-2345-6789"));

		byte[] bytes = writer.write(rows);

		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			Sheet sheet = workbook.getSheetAt(0);
			assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("이름");
			assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("김민준");
			// 휴대폰 번호가 숫자 타입으로 저장되면 앞자리 0이 사라지므로 반드시 문자열이어야 한다
			assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("010-2345-6789");
		}
	}

	@Test
	@DisplayName("행마다 열 개수가 달라도(들쭉날쭉한 데이터) 오류 없이 변환된다")
	void unevenRows() throws IOException {
		byte[] bytes = writer.write(List.of(
			List.of("a", "b", "c"),
			List.of("1")));

		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
		}
	}
}
