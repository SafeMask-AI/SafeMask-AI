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
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 첨부 파일에서 GPT에 전달할 텍스트를 추출합니다.
 *
 * <p>민감정보 보호 원칙상 추출된 텍스트도 일반 입력과 동일하게 마스킹 파이프라인을 거친 뒤
 * GPT로 전달됩니다. 현재는 외부 라이브러리 없이 JDK로 안정적으로 처리 가능한 txt/csv/xlsx/docx를
 * 지원하고, PDF는 파일 메타데이터만 전달합니다.
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
				case "docx" -> limit(extractDocx(file.getBytes()));
				case "pdf" -> "PDF 파일은 첨부되었습니다. 현재 단계에서는 파일명/크기만 참고할 수 있습니다. "
					+ "본문 추출은 PDF 처리 모듈 연결 후 지원됩니다. 크기: " + file.getSize() + " bytes";
				default -> throw new CustomException(ErrorCode.INVALID_REQUEST);
			};
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST);
		}
	}

	private String extractXlsx(byte[] bytes) throws IOException {
		List<String> sharedStrings = new ArrayList<>();
		StringBuilder sheets = new StringBuilder();

		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				String name = entry.getName();
				String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
				if ("xl/sharedStrings.xml".equals(name)) {
					sharedStrings = extractXmlTextItems(xml);
				} else if (name.startsWith("xl/worksheets/") && name.endsWith(".xml")) {
					sheets.append(extractSheetText(xml, sharedStrings)).append("\n");
				}
			}
		}
		return sheets.toString().trim();
	}

	private String extractDocx(byte[] bytes) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if ("word/document.xml".equals(entry.getName())) {
					String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
					return normalizeXmlText(xml);
				}
			}
		}
		return "";
	}

	private String extractSheetText(String xml, List<String> sharedStrings) {
		StringBuilder result = new StringBuilder();
		String[] cells = xml.split("<c ");
		for (String cell : cells) {
			if (!cell.contains("</c>")) {
				continue;
			}
			String value = extractBetween(cell, "<v>", "</v>");
			String inline = extractBetween(cell, "<t>", "</t>");
			if (cell.contains("t=\"s\"") && value != null) {
				int index = Integer.parseInt(value.trim());
				if (index >= 0 && index < sharedStrings.size()) {
					result.append(sharedStrings.get(index)).append('\t');
				}
			} else if (inline != null) {
				result.append(unescapeXml(inline)).append('\t');
			} else if (value != null) {
				result.append(value.trim()).append('\t');
			}
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
