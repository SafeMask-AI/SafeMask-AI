package haitai.safemask.domain.chatmessage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import haitai.safemask.domain.chatmessage.config.AttachmentProcessingProperties;
import haitai.safemask.domain.fileasset.service.FileUploadPolicy;
import haitai.safemask.global.exception.CustomException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * AttachmentTextExtractor 단위 테스트입니다.
 *
 * <p>핵심 검증 대상: 추출 실패는 곧 "민감정보가 마스킹 없이 누락"되는 보안 문제이므로,
 * 생성 도구에 따라 달라지는 xlsx 표기(네임스페이스 접두사 등)와 다중 시트에서도
 * 텍스트가 유실되지 않아야 합니다.
 */
class AttachmentTextExtractorTest {

	private final AttachmentTextExtractor extractor = new AttachmentTextExtractor(
		new FileUploadPolicy(), new AttachmentProcessingProperties());

	@Test
	@DisplayName("xlsx의 문자열·숫자 셀이 추출되고, 셀은 탭·행은 줄바꿈으로 구분된다")
	void extractsCellsWithTableStructure() throws IOException {
		byte[] xlsx = buildXlsx(new String[][] {
			{"이름", "휴대폰"},
			{"김민준", "010-2345-6789"}
		});

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "test.xlsx", "application/vnd.ms-excel", xlsx)));

		// 1행(헤더)과 2행(데이터)이 서로 다른 줄에 있어야 GPT가 표 구조를 이해한다
		assertThat(extracted).contains("이름\t휴대폰");
		assertThat(extracted).contains("김민준\t010-2345-6789");
		assertThat(extracted).doesNotContain("휴대폰\t김민준");
		assertThat(extracted).contains("[첨부: test.xlsx]");
		assertThat(extracted).doesNotContain("[첨부 파일]", "--- test.xlsx ---");
	}

	@Test
	@DisplayName("시트가 여러 개인 xlsx는 모든 시트가 시트명과 함께 추출된다")
	void extractsAllSheetsWithNames() throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			writeSheet(workbook.createSheet("안내"), new String[][] {{"사용 안내 문서입니다"}});
			writeSheet(workbook.createSheet("고객명단"), new String[][] {{"박지훈", "010-1111-2222"}});
			writeSheet(workbook.createSheet("상담기록"), new String[][] {{"이서연", "010-3333-4444"}});
			workbook.write(out);
		}

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "multi.xlsx", "application/vnd.ms-excel", out.toByteArray())));

		// 첫 시트만 읽으면 뒤 시트의 민감정보가 통째로 누락된다 — 전부 있어야 한다
		assertThat(extracted).contains("[시트: 안내]", "[시트: 고객명단]", "[시트: 상담기록]");
		assertThat(extracted).contains("박지훈", "010-1111-2222", "이서연", "010-3333-4444");
	}

	@Test
	@DisplayName("3만 자를 넘는 400행 업무용 xlsx도 새 파일 한도 안에서는 전체 추출된다")
	void extractsFourHundredBusinessRows() throws IOException {
		String[][] rows = new String[401][8];
		for (int column = 0; column < rows[0].length; column++) {
			rows[0][column] = "업무 항목 " + column;
		}
		for (int row = 1; row < rows.length; row++) {
			for (int column = 0; column < rows[row].length; column++) {
				rows[row][column] = "스위트제로 프로젝트 업무 데이터 %03d-%02d".formatted(row, column);
			}
		}
		byte[] xlsx = buildXlsx(rows);

		String extracted = extractor.extract(List.of(new MockMultipartFile(
			"files", "업무목록.xlsx", "application/vnd.ms-excel", xlsx)));

		assertThat(extracted.length()).isGreaterThan(30_000);
		assertThat(extracted).contains("스위트제로 프로젝트 업무 데이터 001-00");
		assertThat(extracted).contains("스위트제로 프로젝트 업무 데이터 400-07");
	}

	@Test
	@DisplayName("네임스페이스 접두사(<x:row> 등)로 저장된 xlsx도 추출된다")
	void extractsNamespacePrefixedXlsx() throws IOException {
		// 일부 생성 도구는 OOXML 태그에 x: 접두사를 붙인다. 태그 문자열 매칭 방식의
		// 수제 파서가 이런 파일에서 추출 0자를 냈던 회귀 방지 테스트
		byte[] xlsx = buildNamespacePrefixedXlsx();

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "prefixed.xlsx", "application/vnd.ms-excel", xlsx)));

		assertThat(extracted).contains("김민준", "010-2345-6789");
	}

	@Test
	@DisplayName("docx 본문도 첨부 텍스트로 추출되어 마스킹 대상이 된다")
	void docxTextExtractedForMaskingPipeline() throws IOException {
		byte[] docx = buildDocx("담당자 김민수 연락처 010-2345-6789");

		String extracted = extractor.extract(List.of(new MockMultipartFile("files", "word-test.docx",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)));

		assertThat(extracted).contains("word-test.docx", "김민수", "010-2345-6789");
	}

	@Test
	@DisplayName("PDF 본문은 페이지 표시와 함께 추출되어 마스킹 대상이 된다")
	void pdfTextExtractedForMaskingPipeline() throws IOException {
		byte[] pdf = buildPdf("contact user@example.com or 010-2345-6789");

		String extracted = extractor.extract(List.of(new MockMultipartFile(
			"files", "document.pdf", "application/pdf", pdf)));

		assertThat(extracted).contains("document.pdf", "[페이지 1]", "user@example.com", "010-2345-6789");
	}

	@Test
	@DisplayName("텍스트 레이어가 없는 PDF는 읽은 것처럼 처리하지 않고 OCR 안내와 함께 거절한다")
	void scannedPdfRejected() throws IOException {
		byte[] pdf;
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			document.addPage(new PDPage());
			document.save(out);
			pdf = out.toByteArray();
		}

		assertThatThrownBy(() -> extractor.extract(List.of(new MockMultipartFile(
			"files", "scan.pdf", "application/pdf", pdf))))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("OCR");
	}

	@Test
	@DisplayName("내용이 비어 있는 텍스트 파일은 검사 0건으로 오인하지 않고 거절한다")
	void blankTextFileRejected() {
		assertThatThrownBy(() -> extractor.extract(List.of(new MockMultipartFile(
			"files", "blank.txt", "text/plain", "   \n\t".getBytes(StandardCharsets.UTF_8)))))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("텍스트를 찾지 못했습니다");
	}

	@Test
	@DisplayName("허용 개수를 초과한 첨부 파일은 처리하지 않는다")
	void rejectsTooManyFiles() {
		List<MultipartFile> files = List.of(
			textFile("1.txt"), textFile("2.txt"), textFile("3.txt"),
			textFile("4.txt"), textFile("5.txt"), textFile("6.txt"));

		assertThatThrownBy(() -> extractor.extract(files))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("최대 5개");
	}

	@Test
	@DisplayName("지원하지 않는 확장자는 처리하지 않는다")
	void rejectsUnsupportedExtension() {
		assertThatThrownBy(() -> extractor.extract(List.of(
			new MockMultipartFile("files", "malware.exe", "application/octet-stream", "x".getBytes()))))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("지원하지 않는 파일 형식");
	}

	@Test
	@DisplayName("추출 텍스트가 너무 긴 파일은 앞부분만 보내지 않고 거절한다")
	void rejectsOversizedExtractedText() {
		String text = "가".repeat(120_001);

		assertThatThrownBy(() -> extractor.extract(List.of(
			new MockMultipartFile("files", "long.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)))))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("120,001자")
			.hasMessageContaining("120,000자");
	}

	@Test
	@DisplayName("파일별 한도 안이어도 여러 파일의 추출 내용 합계가 요청 한도를 넘으면 거절한다")
	void rejectsOversizedCombinedExtractedText() {
		MockMultipartFile first = new MockMultipartFile(
			"files", "first.txt", "text/plain", "가".repeat(100_000).getBytes(StandardCharsets.UTF_8));
		MockMultipartFile second = new MockMultipartFile(
			"files", "second.txt", "text/plain", "나".repeat(80_001).getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> extractor.extract(List.of(first, second)))
			.isInstanceOf(CustomException.class)
			.hasMessageContaining("전체 내용이 180,001자")
			.hasMessageContaining("180,000자");
	}

	// ==== 테스트 데이터 ====

	/** POI로 단일 시트 xlsx를 만듭니다 (MS 엑셀이 저장하는 표준 형태) */
	private byte[] buildXlsx(String[][] rows) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			writeSheet(workbook.createSheet("Sheet1"), rows);
			workbook.write(out);
		}
		return out.toByteArray();
	}

	private MockMultipartFile textFile(String name) {
		return new MockMultipartFile("files", name, "text/plain", "test".getBytes(StandardCharsets.UTF_8));
	}

	private void writeSheet(Sheet sheet, String[][] rows) {
		for (int r = 0; r < rows.length; r++) {
			Row row = sheet.createRow(r);
			for (int c = 0; c < rows[r].length; c++) {
				row.createCell(c).setCellValue(rows[r][c]);
			}
		}
	}

	/**
	 * 태그마다 네임스페이스 접두사(x:)가 붙은 최소 구성 xlsx를 직접 조립합니다.
	 * (POI는 접두사 없는 표준형만 저장하므로, 문제가 됐던 실제 파일의 형태를 재현하려면
	 * zip을 손으로 만들어야 합니다)
	 */
	private byte[] buildNamespacePrefixedXlsx() throws IOException {
		String main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
		String contentTypes = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
			<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
			<Default Extension="xml" ContentType="application/xml"/>
			<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
			<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
			<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
			</Types>""";
		String rootRels = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
			<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
			</Relationships>""";
		String workbook = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<x:workbook xmlns:x="%s" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
			<x:sheets><x:sheet name="고객" sheetId="1" r:id="rId1"/></x:sheets></x:workbook>""".formatted(main);
		String workbookRels = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
			<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
			<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
			</Relationships>""";
		String sharedStrings = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<x:sst xmlns:x="%s" count="2" uniqueCount="2">
			<x:si><x:t>김민준</x:t></x:si><x:si><x:t>010-2345-6789</x:t></x:si></x:sst>""".formatted(main);
		String sheet = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<x:worksheet xmlns:x="%s"><x:sheetData>
			<x:row r="1"><x:c r="A1" t="s"><x:v>0</x:v></x:c><x:c r="B1" t="s"><x:v>1</x:v></x:c></x:row>
			</x:sheetData></x:worksheet>""".formatted(main);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			putEntry(zip, "[Content_Types].xml", contentTypes);
			putEntry(zip, "_rels/.rels", rootRels);
			putEntry(zip, "xl/workbook.xml", workbook);
			putEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels);
			putEntry(zip, "xl/sharedStrings.xml", sharedStrings);
			putEntry(zip, "xl/worksheets/sheet1.xml", sheet);
		}
		return out.toByteArray();
	}

	private void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(content.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private byte[] buildDocx(String text) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (XWPFDocument document = new XWPFDocument()) {
			document.createParagraph().createRun().setText(text);
			document.write(out);
		}
		return out.toByteArray();
	}

	private byte[] buildPdf(String text) throws IOException {
		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				content.newLineAtOffset(40, 750);
				content.showText(text);
				content.endText();
			}
			document.save(out);
			return out.toByteArray();
		}
	}
}
