package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부 파일에서 GPT에 전달할 텍스트를 추출합니다.
 *
 * <p>민감정보 보호 원칙상 추출된 텍스트도 일반 입력과 동일하게 마스킹 파이프라인을 거친 뒤
 * GPT로 전달됩니다. 즉, Word/Excel 파일 안의 이름·전화번호·계좌번호도 먼저 토큰으로 치환된 다음
 * 모델에 전달됩니다.
 *
 * <p>txt/csv는 UTF-8 텍스트로 바로 읽고, Office 문서(xlsx/doc/docx)는 Apache POI로 추출합니다.
 * 추출 실패는 곧 "민감정보가 탐지 없이 누락"되는 보안 문제이므로, 형식 변형에 강한
 * 표준 파서를 사용하는 것이 원칙입니다. PDF는 아직 본문 추출기를 연결하지 않았기 때문에
 * 파일명/크기 안내만 전달합니다.
 */
@Component
public class AttachmentTextExtractor {

	private static final int MAX_EXTRACTED_CHARS_PER_FILE = 30_000;

	public String extract(List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return "";
		}

		StringBuilder builder = new StringBuilder();
		builder.append("\n\n[첨부 파일]\n");
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				continue;
			}
			builder.append("\n--- ").append(file.getOriginalFilename()).append(" ---\n");
			builder.append(extractOne(file)).append("\n");
		}
		return builder.toString();
	}

	private String extractOne(MultipartFile file) {
		String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		String extension = extensionOf(filename);
		try {
			return switch (extension) {
				case "txt", "csv" -> limit(new String(file.getBytes(), StandardCharsets.UTF_8));
				case "xlsx" -> limit(extractXlsx(file.getBytes()));
				case "doc" -> limit(extractDoc(file.getBytes()));
				case "docx" -> limit(extractDocx(file.getBytes()));
				case "pdf" -> "PDF 파일은 첨부되었습니다. 현재 단계에서는 파일명/크기만 참고할 수 있습니다. "
					+ "본문 추출은 PDF 처리 모듈 연결 후 지원됩니다. 크기: " + file.getSize() + " bytes";
				default -> throw new CustomException(ErrorCode.INVALID_REQUEST);
			};
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}

	/**
	 * xlsx의 모든 시트에서 텍스트를 추출합니다. 셀은 탭, 행은 줄바꿈으로 구분하고,
	 * 시트가 여러 개면 어느 시트의 내용인지 알 수 있게 "[시트: 이름]" 표시를 남깁니다.
	 * (시트 구분이 있어야 GPT가 카테고리별 데이터를 올바르게 이해합니다)
	 *
	 * <p>POI를 쓰는 이유(수제 XML 파서에서 교체): 같은 xlsx라도 생성 도구에 따라
	 * XML에 네임스페이스 접두사({@code <x:row>} 등)가 붙거나 inlineStr 표기를 쓰는 등
	 * 형태가 달라서, 태그 문자열 매칭 방식은 특정 도구가 만든 파일에서 추출 0자
	 * (= 민감정보가 탐지 없이 통째로 누락)가 났습니다. POI는 OOXML 표준 전체를
	 * 해석하므로 이런 표기 차이에 안전합니다.
	 */
	private String extractXlsx(byte[] bytes) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			// DataFormatter는 셀의 "표시값"을 그대로 돌려준다 — 문자열로 저장된
			// 전화번호("010-...")의 앞자리 0이나 하이픈이 숫자 변환으로 훼손되지 않는다
			DataFormatter formatter = new DataFormatter();
			boolean multiSheet = workbook.getNumberOfSheets() > 1;

			StringBuilder out = new StringBuilder();
			for (Sheet sheet : workbook) {
				if (multiSheet) {
					out.append("[시트: ").append(sheet.getSheetName()).append("]\n");
				}
				for (Row row : sheet) {
					StringBuilder line = new StringBuilder();
					for (Cell cell : row) {
						line.append(formatter.formatCellValue(cell)).append('\t');
					}
					String lineText = line.toString().stripTrailing();
					if (!lineText.isEmpty()) {
						out.append(lineText).append('\n');
					}
				}
				out.append('\n');
			}
			return out.toString().trim();
		}
	}

	/**
	 * 구형 Word(.doc)에서 본문 텍스트를 추출합니다.
	 *
	 * <p>.doc는 zip 기반의 OOXML(.docx)이 아니라 OLE2 바이너리 형식입니다.
	 * 직접 바이트를 문자열로 변환하면 대부분 깨지고 민감정보 탐지가 실패하므로
	 * POI의 HWPF/WordExtractor가 문서 내부 스트림을 해석하도록 맡깁니다.
	 */
	private String extractDoc(byte[] bytes) throws IOException {
		try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
			 WordExtractor extractor = new WordExtractor(document)) {
			return normalizeExtractedText(extractor.getText());
		}
	}

	/**
	 * Word(.docx)에서 본문 텍스트를 추출합니다.
	 *
	 * <p>.docx도 zip 안의 word/document.xml만 읽으면 단순 문단 일부는 보이지만,
	 * 표·머리글·바닥글·각주처럼 Word가 별도 파트로 저장하는 내용은 빠질 수 있습니다.
	 * 사용자는 "문서 전체가 마스킹 대상"이라고 기대하므로 Word 전용 추출기를 사용해
	 * 가능한 모든 텍스트를 마스킹 파이프라인에 태웁니다.
	 */
	private String extractDocx(byte[] bytes) throws IOException {
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
			 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			return normalizeExtractedText(extractor.getText());
		}
	}

	/**
	 * Office 추출기가 반환한 공백을 마스킹 엔진이 처리하기 좋은 형태로 정리합니다.
	 *
	 * <p>연속 공백은 하나로 줄이되 줄바꿈은 유지합니다. 줄바꿈을 보존해야 표/문단의 경계가 남고,
	 * 미리보기에서도 사용자가 어느 위치의 값이 마스킹되는지 확인하기 쉽습니다.
	 */
	private String normalizeExtractedText(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		return text.replace("\r\n", "\n")
			.replace('\r', '\n')
			.replaceAll("[\\t\\x0B\\f ]+", " ")
			.replaceAll(" *\\n *", "\n")
			.replaceAll("\\n{3,}", "\n\n")
			.trim();
	}

	private String extensionOf(String filename) {
		int dot = filename.lastIndexOf('.');
		return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
	}

	private String limit(String text) {
		if (text.length() <= MAX_EXTRACTED_CHARS_PER_FILE) {
			return text;
		}
		return text.substring(0, MAX_EXTRACTED_CHARS_PER_FILE)
			+ "\n...파일이 길어 앞부분만 추출했습니다.";
	}
}
