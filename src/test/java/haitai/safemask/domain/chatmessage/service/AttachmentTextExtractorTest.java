package haitai.safemask.domain.chatmessage.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * AttachmentTextExtractor 단위 테스트입니다.
 *
 * <p>핵심 검증 대상은 xlsx의 zip 엔트리 순서 문제입니다:
 * 문자열 셀 값이 모여 있는 sharedStrings.xml이 시트보다 뒤에 오는 파일(생성 도구에 따라 실제 존재)에서도
 * 텍스트가 유실되지 않아야 합니다. 유실되면 민감정보가 마스킹 대상에서 통째로 빠지는 보안 문제가 됩니다.
 */
class AttachmentTextExtractorTest {

	private final AttachmentTextExtractor extractor = new AttachmentTextExtractor();

	@Test
	@DisplayName("sharedStrings가 시트보다 뒤에 있는 xlsx에서도 문자열 셀이 유실되지 않는다")
	void sharedStringsAfterSheetsStillExtracted() throws IOException {
		// 시트 → sharedStrings 순서로 zip을 구성 (문제가 됐던 실제 파일과 같은 배치)
		byte[] xlsx = buildXlsx(true);

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "test.xlsx", "application/vnd.ms-excel", xlsx)));

		assertThat(extracted).contains("김민준", "010-2345-6789");
	}

	@Test
	@DisplayName("sharedStrings가 시트보다 앞에 있는 일반적인 xlsx도 동일하게 추출된다")
	void sharedStringsBeforeSheetsStillExtracted() throws IOException {
		byte[] xlsx = buildXlsx(false);

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "test.xlsx", "application/vnd.ms-excel", xlsx)));

		assertThat(extracted).contains("김민준", "010-2345-6789");
	}

	@Test
	@DisplayName("셀은 탭, 행은 줄바꿈으로 구분되어 표 구조가 유지된다")
	void rowsSeparatedByNewline() throws IOException {
		byte[] xlsx = buildXlsx(true);

		String extracted = extractor.extract(List.of(
			new MockMultipartFile("files", "test.xlsx", "application/vnd.ms-excel", xlsx)));

		// 1행(헤더)과 2행(데이터)이 서로 다른 줄에 있어야 한다
		assertThat(extracted).contains("이름\t휴대폰");
		assertThat(extracted).contains("김민준\t010-2345-6789");
		assertThat(extracted).doesNotContain("휴대폰\t김민준");
	}

	@Test
	@DisplayName("docx 본문도 첨부 텍스트로 추출되어 마스킹 대상이 된다")
	void docxTextExtractedForMaskingPipeline() throws IOException {
		byte[] docx = buildDocx("담당자 김민수 연락처 010-2345-6789");

		String extracted = extractor.extract(List.of(new MockMultipartFile("files", "word-test.docx",
			"application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx)));

		assertThat(extracted).contains("word-test.docx", "김민수", "010-2345-6789");
	}

	/**
	 * 최소 구성의 xlsx(zip)를 만듭니다. 내용: 헤더(이름/휴대폰) + 데이터 1행(김민준/010-2345-6789).
	 *
	 * @param sharedStringsLast true면 sharedStrings.xml을 시트 뒤에 배치 (엔트리 순서 문제 재현용)
	 */
	private byte[] buildXlsx(boolean sharedStringsLast) throws IOException {
		String sharedStrings = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="4" uniqueCount="4">
			<si><t>이름</t></si><si><t>휴대폰</t></si><si><t>김민준</t></si><si><t>010-2345-6789</t></si>
			</sst>""";
		String sheet = """
			<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
			<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
			<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
			<row r="2"><c r="A2" t="s"><v>2</v></c><c r="B2" t="s"><v>3</v></c></row>
			</sheetData></worksheet>""";

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			if (!sharedStringsLast) {
				putEntry(zip, "xl/sharedStrings.xml", sharedStrings);
			}
			putEntry(zip, "xl/worksheets/sheet1.xml", sheet);
			if (sharedStringsLast) {
				putEntry(zip, "xl/sharedStrings.xml", sharedStrings);
			}
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
}
