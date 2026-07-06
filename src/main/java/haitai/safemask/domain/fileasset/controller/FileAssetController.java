package haitai.safemask.domain.fileasset.controller;

import haitai.safemask.domain.fileasset.service.FileAssetService;
import haitai.safemask.domain.fileasset.service.FileAssetService.DownloadFile;
import haitai.safemask.domain.member.entity.Member;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/files")
public class FileAssetController {

	private final FileAssetService fileAssetService;

	/**
	 * AI가 생성한 파일(또는 업로드 파일)을 내려받습니다.
	 * 채팅방 소유자만 접근 가능하며, JWT 인증이 필요하므로 프런트는
	 * Authorization 헤더를 실어 fetch로 받아 Blob으로 저장합니다.
	 */
	@GetMapping("/{fileId}/download")
	public ResponseEntity<byte[]> download(@AuthenticationPrincipal Member member,
		@PathVariable Long fileId) {
		DownloadFile file = fileAssetService.download(member, fileId);

		// filename*에 UTF-8 인코딩을 써야 한글 파일명이 모든 브라우저에서 깨지지 않음
		ContentDisposition disposition = ContentDisposition.attachment()
			.filename(file.fileName(), StandardCharsets.UTF_8)
			.build();

		return ResponseEntity.ok()
			.header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
			.contentType(MediaType.parseMediaType(file.contentType()))
			.body(file.bytes());
	}
}
