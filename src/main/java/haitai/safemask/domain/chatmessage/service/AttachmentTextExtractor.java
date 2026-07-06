package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
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
 * <p>txt/csv는 UTF-8 텍스트로 바로 읽고, xlsx는 최소 XML 파서로 셀 값을 추출합니다.
 * Word 문서는 문단·표·머리글 같은 구조가 여러 XML/바이너리 스트림에 나뉘어 있으므로
 * Apache POI 전용 추출기를 사용합니다. PDF는 아직 본문 추출기를 연결하지 않았기 때문에
 * 파일명/크기 안내만 전달합니다.
 */
@Component
public class AttachmentTextExtractor {

	private static final int MAX_EXTRACTED_CHARS_PER_FILE = 30_000;
	private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");

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
	 * xlsx에서 텍스트를 추출합니다.
	 *
	 * <p>반드시 2패스로 처리해야 합니다: xlsx의 문자열 셀은 실제 값이
	 * xl/sharedStrings.xml에 모여 있고 시트에는 인덱스만 있는데,
	 * zip 안에서 sharedStrings가 시트보다 뒤에 오는 파일도 있습니다(생성 도구마다 다름).
	 * 스트림 순서대로 시트를 바로 변환하면 그런 파일에서 문자열 셀이 전부 유실되어
	 * 민감정보가 마스킹 없이 누락되므로, 1패스에서 시트 XML은 보관만 하고
	 * sharedStrings 확보가 끝난 뒤에 텍스트로 변환합니다.
	 */
	private String extractXlsx(byte[] bytes) throws IOException {
		List<String> sharedStrings = new ArrayList<>();
		List<String> sheetXmls = new ArrayList<>();

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				if ("xl/sharedStrings.xml".equals(name)) {
					sharedStrings = extractXmlTextItems(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
				} else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
					sheetXmls.add(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
				}
			}
		}

		StringBuilder sheets = new StringBuilder();
		for (String sheetXml : sheetXmls) {
			sheets.append(extractSheetText(sheetXml, sharedStrings)).append("\n");
		}
		return sheets.toString().trim();
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
	 * 시트 XML을 "셀은 탭, 행은 줄바꿈"으로 구분된 텍스트로 변환합니다.
	 * 행 구분을 살려야 GPT가 표 구조를 이해할 수 있고,
	 * 마스킹 규칙도 셀 경계(탭/줄바꿈)를 기준으로 값을 탐지할 수 있습니다.
	 */
	private String extractSheetText(String xml, List<String> sharedStrings) {
		StringBuilder result = new StringBuilder();
		String[] rows = xml.split("<row ");
		for (String row : rows) {
			if (!row.contains("</c>")) {
				continue;
			}
			StringBuilder line = new StringBuilder();
			String[] cells = row.split("<c ");
			for (String cell : cells) {
				if (!cell.contains("</c>")) {
					continue;
				}
				String value = extractBetween(cell, "<v>", "</v>");
				String inline = extractBetween(cell, "<t>", "</t>");
				if (cell.contains("t=\"s\"") && value != null) {
					int index = Integer.parseInt(value.trim());
					if (index >= 0 && index < sharedStrings.size()) {
						line.append(sharedStrings.get(index)).append('\t');
					}
				} else if (inline != null) {
					line.append(unescapeXml(inline)).append('\t');
				} else if (value != null) {
					line.append(value.trim()).append('\t');
				}
			}
			result.append(line.toString().stripTrailing()).append('\n');
		}
		return result.toString();
	}

	private List<String> extractXmlTextItems(String xml) {
		List<String> items = new ArrayList<>();
		String[] parts = xml.split("<si>");
		for (String part : parts) {
			if (part.contains("</si>")) {
				items.add(normalizeXmlText(part.substring(0, part.indexOf("</si>"))));
			}
		}
		return items;
	}

	private String normalizeXmlText(String xml) {
		return unescapeXml(XML_TAG.matcher(xml).replaceAll(" "))
			.replaceAll("\\s+", " ")
			.trim();
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

	private String extractBetween(String text, String start, String end) {
		int startIndex = text.indexOf(start);
		if (startIndex < 0) {
			return null;
		}
		int valueStart = startIndex + start.length();
		int endIndex = text.indexOf(end, valueStart);
		if (endIndex < 0) {
			return null;
		}
		return text.substring(valueStart, endIndex);
	}

	private String unescapeXml(String text) {
		return text.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&amp;", "&")
			.replace("&quot;", "\"")
			.replace("&apos;", "'");
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
