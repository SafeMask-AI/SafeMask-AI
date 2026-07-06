package haitai.safemask.domain.fileasset.service;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.fileasset.entity.FileAsset;
import haitai.safemask.domain.fileasset.enums.FileAssetStatus;
import haitai.safemask.domain.fileasset.repository.FileAssetRepository;
import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.global.exception.CustomException;
import haitai.safemask.global.exception.ErrorCode;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 업로드 원본 보관과 파일 다운로드 등 FileAsset 흐름을 담당합니다.
 * (AI 산출물 생성은 GeneratedFileService, 실체 저장은 FileStorageService가 담당)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FileAssetService {

	private final FileAssetRepository fileAssetRepository;
	private final FileStorageService fileStorageService;

	/**
	 * 사용자가 첨부한 원본 파일들을 사내 스토리지에 보관합니다.
	 * 이 원본은 AI 편집 요청 시 "카피의 출발점"으로만 읽히며 절대 수정되지 않습니다.
	 * 파일 하나가 저장에 실패해도 채팅 흐름은 계속돼야 하므로 로그만 남기고 건너뜁니다.
	 */
	@Transactional
	public void storeUploads(ChatRoom chatRoom, List<MultipartFile> files) {
		if (files == null) {
			return;
		}

		for (MultipartFile file : files) {
			if (file == null || file.isEmpty() || file.getOriginalFilename() == null) {
				continue;
			}
			try {
				String originalName = file.getOriginalFilename();
				String extension = extensionOf(originalName);
				String storedPath = fileStorageService.store(file.getBytes(), extension.isBlank() ? "bin" : extension);
				String contentType = file.getContentType() == null
					? "application/octet-stream" : file.getContentType();

				fileAssetRepository.save(FileAsset.createUploaded(
					chatRoom, originalName, storedPath, contentType, file.getSize()));
			} catch (IOException | RuntimeException e) {
				log.error("업로드 파일 보관에 실패했습니다. chatRoomId={}, name={}",
					chatRoom.getId(), file.getOriginalFilename(), e);
			}
		}
	}

	private String extensionOf(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
	}

	/**
	 * 다운로드할 파일 정보와 내용을 담는 결과 객체입니다.
	 */
	public record DownloadFile(String fileName, String contentType, byte[] bytes) {
	}

	/**
	 * 파일을 다운로드용으로 읽습니다.
	 * 요청자가 파일이 속한 채팅방의 소유자가 아니면 존재 여부를 노출하지 않기 위해
	 * 권한 오류가 아닌 NOT_FOUND로 응답합니다.
	 */
	public DownloadFile download(Member member, Long fileId) {
		FileAsset asset = fileAssetRepository.findByIdAndChatRoom_Member_Id(fileId, member.getId())
			.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
		if (asset.getStatus() == FileAssetStatus.DELETED) {
			throw new CustomException(ErrorCode.NOT_FOUND);
		}

		byte[] bytes = fileStorageService.load(asset.getStoredPath());
		return new DownloadFile(asset.getOriginalName(), asset.getContentType(), bytes);
	}

	/**
	 * 채팅방에 연결된 파일 실체를 삭제하고 DB 상태를 DELETED로 남깁니다.
	 * 아카이브 시 호출되어 서버 디스크에 업로드/생성 파일이 계속 누적되지 않게 합니다.
	 */
	@Transactional
	public void deleteFilesForChatRoom(Long chatRoomId) {
		List<FileAsset> assets = fileAssetRepository.findByChatRoom_IdAndStatusNot(chatRoomId, FileAssetStatus.DELETED);
		for (FileAsset asset : assets) {
			fileStorageService.delete(asset.getStoredPath());
			asset.markDeleted();
		}
	}
}
