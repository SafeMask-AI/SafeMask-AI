package haitai.safemask.domain.chatmessage.service;

import haitai.safemask.domain.chatmessage.dto.ManualMaskRequest;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.chatroom.enums.ChatRoomStatus;
import haitai.safemask.domain.chatroom.repository.ChatRoomRepository;
import haitai.safemask.domain.masking.dto.Detection;
import haitai.safemask.domain.masking.dto.MaskingResult;
import haitai.safemask.domain.masking.service.MaskingService;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.fileasset.service.WordRunTextReplacer;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import lombok.RequiredArgsConstructor;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFFootnote;
import org.apache.poi.xwpf.usermodel.XWPFEndnote;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaskingPreviewDownloadService {

	private final ChatRoomRepository chatRoomRepository;
	private final MaskingService maskingService;
	private final AttachmentTextExtractor attachmentTextExtractor;

	public record PreviewDownloadFile(String fileName, String contentType, byte[] bytes) {
	}

	public PreviewDownloadFile build(Member member, Long chatRoomId, String content,
		List<ManualMaskRequest> manualMasks, List<MultipartFile> files) {
		ChatRoom chatRoom = chatRoomRepository.findByIdAndMember_IdAndStatus(chatRoomId, member.getId(),
				ChatRoomStatus.ACTIVE)
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));

		if (files == null || files.isEmpty()) {
			String masked = mask(chatRoom.getId(), content == null ? "" : content, manualMasks);
			return new PreviewDownloadFile("masked-preview.txt", "text/plain;charset=UTF-8",
				masked.getBytes(StandardCharsets.UTF_8));
		}

		List<MultipartFile> actualFiles = files.stream()
			.filter(file -> file != null && !file.isEmpty())
			.toList();
		if (actualFiles.size() == 1) {
			return maskSingleFile(chatRoom.getId(), actualFiles.get(0), manualMasks);
		}
		return zipMaskedFiles(chatRoom.getId(), actualFiles, manualMasks);
	}

	private PreviewDownloadFile zipMaskedFiles(Long chatRoomId, List<MultipartFile> files,
		List<ManualMaskRequest> manualMasks) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			 ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (MultipartFile file : files) {
				PreviewDownloadFile masked = maskSingleFile(chatRoomId, file, manualMasks);
				zip.putNextEntry(new ZipEntry(masked.fileName()));
				zip.write(masked.bytes());
				zip.closeEntry();
			}
			zip.finish();
			return new PreviewDownloadFile("masked-preview.zip", "application/zip", out.toByteArray());
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INTERNAL_SERVER_ERROR, e);
		}
	}

	private PreviewDownloadFile maskSingleFile(Long chatRoomId, MultipartFile file,
		List<ManualMaskRequest> manualMasks) {
		String originalName = file.getOriginalFilename() == null ? "input.txt" : file.getOriginalFilename();
		String extension = extensionOf(originalName);
		try {
			return switch (extension) {
				case "txt", "md" -> textFile(chatRoomId, originalName, extension,
					new String(file.getBytes(), StandardCharsets.UTF_8), manualMasks);
				case "csv" -> csvFile(chatRoomId, originalName,
					new String(file.getBytes(), StandardCharsets.UTF_8), manualMasks);
				case "xlsx" -> xlsxFile(chatRoomId, originalName, file.getBytes(), manualMasks);
				case "docx" -> docxFile(chatRoomId, originalName, file.getBytes(), manualMasks);
				case "doc" -> docFile(chatRoomId, originalName, file.getBytes(), manualMasks);
				default -> extractedTextFile(chatRoomId, originalName, file, manualMasks);
			};
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private PreviewDownloadFile textFile(Long chatRoomId, String originalName, String extension, String text,
		List<ManualMaskRequest> manualMasks) {
		String masked = mask(chatRoomId, text, manualMasks);
		return new PreviewDownloadFile(maskedName(originalName, extension), contentTypeOf(extension),
			masked.getBytes(StandardCharsets.UTF_8));
	}

	private PreviewDownloadFile csvFile(Long chatRoomId, String originalName, String text,
		List<ManualMaskRequest> manualMasks) {
		String masked = mask(chatRoomId, text, manualMasks);
		byte[] body = masked.getBytes(StandardCharsets.UTF_8);
		byte[] withBom = new byte[body.length + 3];
		withBom[0] = (byte) 0xEF;
		withBom[1] = (byte) 0xBB;
		withBom[2] = (byte) 0xBF;
		System.arraycopy(body, 0, withBom, 3, body.length);
		return new PreviewDownloadFile(maskedName(originalName, "csv"), "text/csv;charset=UTF-8", withBom);
	}

	private PreviewDownloadFile xlsxFile(Long chatRoomId, String originalName, byte[] bytes,
		List<ManualMaskRequest> manualMasks) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			DataFormatter formatter = new DataFormatter();
			for (Sheet sheet : workbook) {
				for (Row row : sheet) {
					for (Cell cell : row) {
						String displayValue = formatter.formatCellValue(cell);
						if (displayValue == null || displayValue.isBlank()) {
							continue;
						}
						String masked = mask(chatRoomId, displayValue, manualMasks);
						if (!displayValue.equals(masked)) {
							cell.setCellValue(masked);
						}
					}
				}
			}
			workbook.write(out);
			return new PreviewDownloadFile(maskedName(originalName, "xlsx"),
				"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private PreviewDownloadFile docxFile(Long chatRoomId, String originalName, byte[] bytes,
		List<ManualMaskRequest> manualMasks) {
		try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			maskDocxParagraphs(chatRoomId, document.getParagraphs(), manualMasks);
			for (XWPFTable table : document.getTables()) {
				maskDocxTable(chatRoomId, table, manualMasks);
			}
			for (XWPFHeader header : document.getHeaderList()) {
				maskDocxParagraphs(chatRoomId, header.getParagraphs(), manualMasks);
				for (XWPFTable table : header.getTables()) maskDocxTable(chatRoomId, table, manualMasks);
			}
			for (XWPFFooter footer : document.getFooterList()) {
				maskDocxParagraphs(chatRoomId, footer.getParagraphs(), manualMasks);
				for (XWPFTable table : footer.getTables()) maskDocxTable(chatRoomId, table, manualMasks);
			}
			for (XWPFFootnote footnote : document.getFootnotes()) {
				maskDocxParagraphs(chatRoomId, footnote.getParagraphs(), manualMasks);
				for (XWPFTable table : footnote.getTables()) maskDocxTable(chatRoomId, table, manualMasks);
			}
			for (XWPFEndnote endnote : document.getEndnotes()) {
				maskDocxParagraphs(chatRoomId, endnote.getParagraphs(), manualMasks);
				for (XWPFTable table : endnote.getTables()) maskDocxTable(chatRoomId, table, manualMasks);
			}
			document.write(out);
			return new PreviewDownloadFile(maskedName(originalName, "docx"),
				"application/vnd.openxmlformats-officedocument.wordprocessingml.document", out.toByteArray());
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private void maskDocxTable(Long chatRoomId, XWPFTable table, List<ManualMaskRequest> manualMasks) {
		for (XWPFTableRow row : table.getRows()) {
			for (XWPFTableCell cell : row.getTableCells()) {
				maskDocxParagraphs(chatRoomId, cell.getParagraphs(), manualMasks);
				for (XWPFTable nestedTable : cell.getTables()) {
					maskDocxTable(chatRoomId, nestedTable, manualMasks);
				}
			}
		}
	}

	private void maskDocxParagraphs(Long chatRoomId, List<XWPFParagraph> paragraphs,
		List<ManualMaskRequest> manualMasks) {
		for (XWPFParagraph paragraph : paragraphs) {
			String text = paragraph.getText();
			if (text == null || text.isBlank()) {
				continue;
			}
			Map<String, String> replacements = replacementsFor(chatRoomId, text, manualMasks);
			replacements.forEach((original, token) -> WordRunTextReplacer.replace(paragraph, original, token));
		}
	}

	private PreviewDownloadFile docFile(Long chatRoomId, String originalName, byte[] bytes,
		List<ManualMaskRequest> manualMasks) {
		try (HWPFDocument document = new HWPFDocument(new ByteArrayInputStream(bytes));
			 WordExtractor extractor = new WordExtractor(document);
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Map<String, String> replacements = replacementsFor(chatRoomId, extractor.getText(), manualMasks);
			Range range = document.getRange();
			replacements.forEach(range::replaceText);
			document.write(out);
			return new PreviewDownloadFile(maskedName(originalName, "doc"), "application/msword", out.toByteArray());
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private PreviewDownloadFile extractedTextFile(Long chatRoomId, String originalName, MultipartFile file,
		List<ManualMaskRequest> manualMasks) {
		String extracted = attachmentTextExtractor.extract(List.of(file));
		String masked = mask(chatRoomId, extracted, manualMasks);
		String baseName = stripExtension(originalName);
		return new PreviewDownloadFile(baseName + "_masked.txt", "text/plain;charset=UTF-8",
			masked.getBytes(StandardCharsets.UTF_8));
	}

	private String mask(Long chatRoomId, String text, List<ManualMaskRequest> manualMasks) {
		MaskingResult current = maskingService.mask(chatRoomId, text);
		if (manualMasks == null || manualMasks.isEmpty()) {
			return current.maskedText();
		}

		for (ManualMaskRequest manualMask : manualMasks) {
			if (manualMask == null || manualMask.value() == null || manualMask.value().isBlank()) {
				continue;
			}
			MaskingType type = manualMask.type() == null ? MaskingType.CUSTOM : manualMask.type();
			current = maskingService.maskManually(chatRoomId, current.maskedText(), manualMask.value(), type);
		}
		return current.maskedText();
	}

	private Map<String, String> replacementsFor(Long chatRoomId, String text, List<ManualMaskRequest> manualMasks) {
		Map<String, String> replacements = new LinkedHashMap<>();
		MaskingResult current = maskingService.mask(chatRoomId, text);
		addReplacements(replacements, current.detections());
		if (manualMasks == null || manualMasks.isEmpty()) {
			return replacements;
		}

		for (ManualMaskRequest manualMask : manualMasks) {
			if (manualMask == null || manualMask.value() == null || manualMask.value().isBlank()) {
				continue;
			}
			MaskingType type = manualMask.type() == null ? MaskingType.CUSTOM : manualMask.type();
			current = maskingService.maskManually(chatRoomId, current.maskedText(), manualMask.value(), type);
			addReplacements(replacements, current.detections());
		}
		return replacements;
	}

	private void addReplacements(Map<String, String> replacements, List<Detection> detections) {
		for (Detection detection : detections) {
			if (detection.originalValue() != null && !detection.originalValue().isBlank()) {
				replacements.putIfAbsent(detection.originalValue(), detection.token());
			}
		}
	}

	private String maskedName(String originalName, String extension) {
		return stripExtension(originalName) + "_masked." + extension;
	}

	private String stripExtension(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot > 0 ? fileName.substring(0, dot) : fileName;
	}

	private String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
	}

	private String contentTypeOf(String extension) {
		return switch (extension) {
			case "csv" -> "text/csv;charset=UTF-8";
			case "md" -> "text/markdown;charset=UTF-8";
			default -> "text/plain;charset=UTF-8";
		};
	}
}
