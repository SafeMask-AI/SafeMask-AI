package haitai.safemask.domain.fileasset.service;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * 2차원 문자열 데이터를 xlsx 파일로 변환합니다. (AI 생성 파일용)
 *
 * <p>모든 셀을 문자열 타입으로 기록합니다. 이 서비스가 다루는 데이터는
 * 전화번호("010-..."), 계좌번호, 사번처럼 앞자리 0이나 하이픈이 의미 있는
 * 식별자가 대부분이라, 숫자 타입으로 저장하면 엑셀이 값을 훼손하기 때문입니다.
 * (예: 010... → 10..., 긴 카드번호 → 지수 표기)
 */
@Component
public class ExcelFileWriter {

	/** 열 너비 자동 조정 상한 (문자 수 기준). 셀 하나가 지나치게 길어도 시트가 깨지지 않게 제한 */
	private static final int MAX_COLUMN_WIDTH_CHARS = 60;

	/**
	 * 행 데이터를 단일 시트 xlsx로 만듭니다. 첫 행은 헤더로 보고 굵게 표시합니다.
	 */
	public byte[] write(List<List<String>> rows) {
		try (XSSFWorkbook workbook = new XSSFWorkbook();
			ByteArrayOutputStream out = new ByteArrayOutputStream()) {

			Sheet sheet = workbook.createSheet("Sheet1");
			CellStyle headerStyle = createHeaderStyle(workbook);

			int columnCount = 0;
			for (int r = 0; r < rows.size(); r++) {
				Row row = sheet.createRow(r);
				List<String> cells = rows.get(r);
				columnCount = Math.max(columnCount, cells.size());
				for (int c = 0; c < cells.size(); c++) {
					Cell cell = row.createCell(c);
					cell.setCellValue(cells.get(c) == null ? "" : cells.get(c));
					if (r == 0) {
						cell.setCellStyle(headerStyle);
					}
				}
			}

			adjustColumnWidths(sheet, rows, columnCount);
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
		}
	}

	private CellStyle createHeaderStyle(XSSFWorkbook workbook) {
		Font font = workbook.createFont();
		font.setBold(true);
		CellStyle style = workbook.createCellStyle();
		style.setFont(font);
		return style;
	}

	/**
	 * 내용 길이에 맞춰 열 너비를 정합니다.
	 * POI의 autoSizeColumn은 행 수만큼 폰트 계산을 반복해 느리므로,
	 * 문자 수 기반 근사치로 충분한 이 용도에는 직접 계산이 낫습니다.
	 * (한글은 영문보다 폭이 넓어 2문자 폭으로 계산)
	 */
	private void adjustColumnWidths(Sheet sheet, List<List<String>> rows, int columnCount) {
		for (int c = 0; c < columnCount; c++) {
			int maxWidth = 8;
			for (List<String> row : rows) {
				if (c < row.size() && row.get(c) != null) {
					maxWidth = Math.max(maxWidth, displayWidth(row.get(c)));
				}
			}
			sheet.setColumnWidth(c, Math.min(maxWidth + 2, MAX_COLUMN_WIDTH_CHARS) * 256);
		}
	}

	private int displayWidth(String value) {
		int width = 0;
		for (int i = 0; i < value.length(); i++) {
			width += value.charAt(i) >= 0x1100 ? 2 : 1;
		}
		return width;
	}
}
