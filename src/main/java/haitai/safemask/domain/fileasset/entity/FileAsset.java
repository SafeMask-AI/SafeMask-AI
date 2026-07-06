package haitai.safemask.domain.fileasset.entity;

import haitai.safemask.domain.chatroom.entity.ChatRoom;
import haitai.safemask.domain.fileasset.enums.FileAssetStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 채팅방에 연결된 파일입니다. 두 종류가 있습니다.
 * <ul>
 *   <li>사용자가 업로드한 파일 (UPLOADED → EXTRACTED → MASKED)</li>
 *   <li>AI 응답에서 생성된 산출물 파일 (GENERATED, 다운로드 대상)</li>
 * </ul>
 * 파일 실체는 사내 스토리지에만 저장됩니다. 생성 파일에는 원복된 민감정보가
 * 들어 있으므로 절대 외부(GPT)로 재전송하지 않으며, 다운로드는 채팅방 소유자만 가능합니다.
 */
@Getter
@Entity
@Table(name = "MASK_FILE_ASSET")
public class FileAsset {

	@Id
	@SequenceGenerator(name = "file_asset_seq_gen", sequenceName = "SAFEMASK_FILE_ASSET_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "file_asset_seq_gen")
	private Long id;

	/** 파일이 업로드된 채팅방 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chatroom_id", nullable = false)
	private ChatRoom chatRoom;

	/** 사용자가 업로드한 원래 파일명 (화면 표시용) */
	@Column(nullable = false, length = 255)
	private String originalName;

	/** 사내 스토리지에 저장된 실제 경로 (충돌 방지를 위해 UUID 등으로 저장) */
	@Column(nullable = false, length = 500)
	private String storedPath;

	/** MIME 타입 (예: application/pdf, text/csv) */
	@Column(nullable = false, length = 100)
	private String contentType;

	/** 파일 크기 (byte) */
	@Column(nullable = false)
	private Long fileSize;

	/** 처리 단계 (업로드 → 텍스트 추출 → 마스킹) */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private FileAssetStatus status;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	protected FileAsset() {
	}

	/** AI 응답에서 만들어진 산출물 파일을 등록합니다. */
	public static FileAsset createGenerated(ChatRoom chatRoom, String originalName, String storedPath,
		String contentType, long fileSize) {
		FileAsset asset = new FileAsset();
		asset.chatRoom = chatRoom;
		asset.originalName = originalName;
		asset.storedPath = storedPath;
		asset.contentType = contentType;
		asset.fileSize = fileSize;
		asset.status = FileAssetStatus.GENERATED;
		return asset;
	}

	/**
	 * 사용자가 업로드한 원본 파일을 등록합니다.
	 * 원본은 읽기 전용으로만 쓰입니다 — AI 편집 요청 시 이 파일을 카피한 사본에만
	 * 수정이 적용되고, 원본 자체는 절대 변경되지 않습니다.
	 */
	public static FileAsset createUploaded(ChatRoom chatRoom, String originalName, String storedPath,
		String contentType, long fileSize) {
		FileAsset asset = new FileAsset();
		asset.chatRoom = chatRoom;
		asset.originalName = originalName;
		asset.storedPath = storedPath;
		asset.contentType = contentType;
		asset.fileSize = fileSize;
		asset.status = FileAssetStatus.UPLOADED;
		return asset;
	}

	/** 파일 실체가 삭제됐음을 DB에 남깁니다. */
	public void markDeleted() {
		this.status = FileAssetStatus.DELETED;
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;

		if (this.status == null) {
			this.status = FileAssetStatus.UPLOADED;
		}
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
