package haitai.safemask.domain.fileasset.repository;

import haitai.safemask.domain.fileasset.entity.FileAsset;
import haitai.safemask.domain.fileasset.enums.FileAssetStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileAssetRepository extends JpaRepository<FileAsset, Long> {

	/**
	 * 다운로드 권한 검증용 조회입니다.
	 * 파일이 속한 채팅방의 소유자가 요청자인 경우에만 파일을 반환하므로,
	 * 파일 ID를 알아낸 다른 사용자가 남의 파일을 내려받는 것을 쿼리 단계에서 차단합니다.
	 */
	Optional<FileAsset> findByIdAndChatRoom_Member_Id(Long id, Long memberId);

	/**
	 * AI 편집 지시가 가리키는 원본 파일을 찾습니다.
	 * 같은 이름으로 여러 번 업로드했을 수 있으므로 가장 최근 것을 사용합니다.
	 */
	Optional<FileAsset> findFirstByChatRoom_IdAndOriginalNameAndStatusOrderByIdDesc(
		Long chatRoomId, String originalName, FileAssetStatus status);

	/**
	 * 편집 지시의 파일명이 업로드 파일명과 정확히 일치하지 않을 때(모델이 이름을 축약하는 경우)
	 * 채팅방의 가장 최근 업로드 파일로 대신 매칭하기 위한 조회입니다.
	 */
	Optional<FileAsset> findFirstByChatRoom_IdAndStatusOrderByIdDesc(Long chatRoomId, FileAssetStatus status);

	/** 채팅방 정리 시 아직 삭제되지 않은 파일만 조회합니다. */
	List<FileAsset> findByChatRoom_IdAndStatusNot(Long chatRoomId, FileAssetStatus status);
}
