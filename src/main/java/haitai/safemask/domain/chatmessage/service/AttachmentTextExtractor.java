package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.domain.chatmessage.config.AttachmentProcessingProperties;
import haitai.safemask.domain.fileasset.service.FileUploadPolicy;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
 * 표준 파서를 사용하는 것이 원칙입니다. PDF는 PDFBox로 페이지별 텍스트를 추출하며,
 * 텍스트 레이어가 없는 스캔 문서는 원문을 읽은 것처럼 처리하지 않고 명확히 거절합니다.
 */
@Component
@RequiredArgsConstructor
public class AttachmentTextExtractor {

	private final FileUploadPolicy fileUploadPolicy;
	private final AttachmentProcessingProperties properties;

	public String extract(List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return "";
		}
		fileUploadPolicy.validate(files);

		StringBuilder builder = new StringBuilder();
		long totalExtractedChars = 0;
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				continue;
			}
			String extracted = extractOne(file);
			if (extracted.isBlank()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST,
					"파일에서 처리할 텍스트를 찾지 못했습니다: " + file.getOriginalFilename());
			}
			totalExtractedChars += extracted.length();
			if (totalExtractedChars > properties.getMaxExtractedCharsPerRequest()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST,
					"첨부 파일에서 추출된 전체 내용이 %,d자로 한 번에 처리 가능한 %,d자를 초과했습니다. "
						.formatted(totalExtractedChars, properties.getMaxExtractedCharsPerRequest())
						+ "파일 수를 줄이거나 필요한 시트와 범위만 남겨 다시 첨부해 주세요.");
			}
			// 파일명은 파일 편집 응답이 정확한 원본을 가리키는 데 필요하다. 다만 일반 헤더와
			// 장식용 구분선은 AI 입력만 늘리므로 파일별 한 줄 표식으로 간결하게 유지한다.
			builder.append("\n\n[첨부: ").append(file.getOriginalFilename()).append("]\n");
			builder.append(extracted).append("\n");
		}
		return builder.toString();
	}

	private String extractOne(MultipartFile file) {
		String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
		String extension = fileUploadPolicy.extensionOf(filename);
		try {
			return switch (extension) {
				case "txt", "csv" -> limit(new String(file.getBytes(), StandardCharsets.UTF_8), filename);
				case "xlsx" -> limit(extractXlsx(file.getBytes(), filename), filename);
				case "doc" -> limit(extractDoc(file.getBytes()), filename);
				case "docx" -> limit(extractDocx(file.getBytes()), filename);
				case "pdf" -> limit(extractPdf(file.getBytes(), filename), filename);
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
	private String extractXlsx(byte[] bytes, String filename) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
			// DataFormatter는 셀의 "표시값"을 그대로 돌려준다 — 문자열로 저장된
			// 전화번호("010-...")의 앞자리 0이나 하이픈이 숫자 변환으로 훼손되지 않는다
			DataFormatter formatter = new DataFormatter();
			boolean multiSheet = workbook.getNumberOfSheets() > 1;

			StringBuilder out = new StringBuilder();
			int visitedCells = 0;
			for (Sheet sheet : workbook) {
				if (multiSheet) {
					out.append("[시트: ").append(sheet.getSheetName()).append("]\n");
				}
				for (Row row : sheet) {
					StringBuilder line = new StringBuilder();
					for (Cell cell : row) {
						if (++visitedCells > properties.getMaxExcelCells()) {
							throw new CustomException(ErrorCode.INVALID_REQUEST,
								"엑셀 셀이 너무 많아 처리할 수 없습니다. 필요한 시트나 범위만 남겨 주세요.");
						}
						line.append(formatter.formatCellValue(cell)).append('\t');
					}
					String lineText = line.toString().stripTrailing();
					if (!lineText.isEmpty()) {
						out.append(lineText).append('\n');
						ensureWithinPerFileLimit(out.length(), filename);
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
	 * PDF의 텍스트 레이어를 페이지 단위로 추출합니다.
	 *
	 * <p>PDF는 화면에 글자가 보여도 실제로는 페이지 이미지뿐인 경우가 있습니다. 빈 추출 결과를
	 * 그대로 AI에 보내면 모델이 문서를 읽었다고 오해할 수 있으므로 스캔/OCR 필요 상태로 거절합니다.
	 * 암호화 PDF는 문서가 허용한 텍스트 추출 권한을 존중합니다.
	 */
	private String extractPdf(byte[] bytes, String filename) throws IOException {
		try (PDDocument document = Loader.loadPDF(bytes)) {
			if (document.getNumberOfPages() > properties.getMaxPdfPages()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST, "PDF 페이지가 너무 많아 처리할 수 없습니다.");
			}
			if (document.isEncrypted() && !document.getCurrentAccessPermission().canExtractContent()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST,
					"텍스트 추출 권한이 없는 PDF입니다. 권한을 해제한 파일을 다시 첨부해 주세요.");
			}

			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			StringBuilder extracted = new StringBuilder();
			for (int page = 1; page <= document.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String pageText = normalizeExtractedText(stripper.getText(document));
				if (!pageText.isBlank()) {
					extracted.append("[페이지 ").append(page).append("]\n")
						.append(pageText).append("\n\n");
				}
				ensureWithinPerFileLimit(extracted.length(), filename);
			}
			if (extracted.isEmpty()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST,
					"텍스트를 읽을 수 없는 PDF입니다. 스캔 문서는 OCR 처리 후 다시 첨부해 주세요.");
			}
			return extracted.toString().trim();
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

	private String limit(String text, String filename) {
		if (text.length() <= properties.getMaxExtractedCharsPerFile()) {
			return text;
		}
		ensureWithinPerFileLimit(text.length(), filename);
		return text;
	}

	private void ensureWithinPerFileLimit(int extractedChars, String filename) {
		if (extractedChars <= properties.getMaxExtractedCharsPerFile()) {
			return;
		}
		throw new CustomException(ErrorCode.INVALID_REQUEST,
			"'%s'에서 추출된 내용이 %,d자로 파일당 허용량 %,d자를 초과했습니다. "
				.formatted(filename, extractedChars, properties.getMaxExtractedCharsPerFile())
				+ "필요한 시트나 범위만 남겨 다시 첨부해 주세요.");
	}
}
