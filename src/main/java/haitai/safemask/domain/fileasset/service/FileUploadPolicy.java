package haitai.safemask.domain.fileasset.service;

import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 파일의 공통 검증 정책입니다.
 *
 * <p>파일은 마스킹·AI 처리·원본 보관·다운로드 흐름을 모두 지나가므로,
 * 컨트롤러나 개별 서비스가 제각각 검증하면 우회 경로가 생길 수 있습니다.
 */
@Component
public class FileUploadPolicy {

	private static final int MAX_FILES_PER_REQUEST = 5;
	private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
	private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "csv", "xlsx", "doc", "docx", "pdf");

	public void validate(List<MultipartFile> files) {
		if (files == null || files.isEmpty()) {
			return;
		}
		if (files.size() > MAX_FILES_PER_REQUEST) {
			throw new CustomException(ErrorCode.INVALID_REQUEST,
				"첨부 파일은 한 번에 최대 " + MAX_FILES_PER_REQUEST + "개까지 업로드할 수 있습니다.");
		}
		for (MultipartFile file : files) {
			validateOne(file);
		}
	}

	public void validateOne(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			return;
		}
		String originalName = file.getOriginalFilename();
		if (originalName == null || originalName.isBlank()) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, "파일명이 없는 첨부 파일은 업로드할 수 없습니다.");
		}
		String extension = extensionOf(originalName);
		if (!ALLOWED_EXTENSIONS.contains(extension)) {
			throw new CustomException(ErrorCode.INVALID_REQUEST,
				"지원하지 않는 파일 형식입니다: " + originalName);
		}
		if (file.getSize() > MAX_FILE_BYTES) {
			throw new CustomException(ErrorCode.INVALID_REQUEST,
				"파일 크기는 10MB 이하만 업로드할 수 있습니다: " + originalName);
		}
		validateSignature(file, extension);
	}

	private void validateSignature(MultipartFile file, String extension) {
		if (Set.of("txt", "csv").contains(extension)) return;
		try {
			byte[] bytes = file.getBytes();
			boolean valid = switch (extension) {
				case "pdf" -> startsWith(bytes, new byte[]{'%', 'P', 'D', 'F', '-'});
				case "xlsx", "docx" -> startsWith(bytes, new byte[]{'P', 'K'});
				case "doc" -> startsWith(bytes, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0});
				default -> false;
			};
			if (!valid) throw new CustomException(ErrorCode.INVALID_REQUEST, "파일 확장자와 실제 형식이 일치하지 않습니다: " + file.getOriginalFilename());
		} catch (IOException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, e);
		}
	}

	private boolean startsWith(byte[] bytes, byte[] signature) {
		if (bytes.length < signature.length) return false;
		for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
		return true;
	}

	public String extensionOf(String fileName) {
		if (fileName == null) {
			return "";
		}
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
