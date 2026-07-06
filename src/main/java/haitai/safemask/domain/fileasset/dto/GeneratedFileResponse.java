package haitai.safemask.domain.fileasset.dto;

import haitai.safemask.domain.fileasset.entity.FileAsset;

/**
 * AI가 생성한 파일의 다운로드 정보입니다. 채팅 응답에 실려 프런트에서 다운로드 카드로 표시됩니다.
 */
public record GeneratedFileResponse(
	Long fileId,
	String fileName,
	long fileSize,
	String downloadUrl
) {
	public static GeneratedFileResponse from(FileAsset asset) {
		return new GeneratedFileResponse(asset.getId(), asset.getOriginalName(), asset.getFileSize(),
			"/api/files/" + asset.getId() + "/download");
	}
}
