package haitai.safemask.domain.chatmessage.approval;

import haitai.safemask.domain.fileasset.service.FileUploadPolicy;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** 첨부 바이트를 복제하지 않고 스트림으로 SHA-256 지문을 계산합니다. */
@Component
@RequiredArgsConstructor
public class FileFingerprintService {

	private final FileUploadPolicy fileUploadPolicy;

	public List<ApprovedFileFingerprint> fingerprint(List<MultipartFile> files) {
		fileUploadPolicy.validate(files);
		if (files == null || files.isEmpty()) {
			return List.of();
		}

		List<ApprovedFileFingerprint> fingerprints = new ArrayList<>();
		for (MultipartFile file : files) {
			if (file == null || file.isEmpty()) {
				continue;
			}
			String originalName = file.getOriginalFilename();
			if (originalName == null || originalName.isBlank()) {
				throw new CustomException(ErrorCode.INVALID_REQUEST, "파일명이 없는 첨부 파일은 처리할 수 없습니다.");
			}
			fingerprints.add(new ApprovedFileFingerprint(originalName, file.getSize(), sha256(file)));
		}
		return List.copyOf(fingerprints);
	}

	private String sha256(MultipartFile file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = file.getInputStream()) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) >= 0) {
					if (read > 0) {
						digest.update(buffer, 0, read);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new CustomException(ErrorCode.INVALID_REQUEST, "첨부 파일의 동일성을 확인하지 못했습니다.");
		}
	}
}
