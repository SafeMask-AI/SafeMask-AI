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
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 사용자가 채팅방에 업로드한 파일입니다.
 * 파일 원본은 사내 스토리지에만 저장되며, 텍스트 추출 → 마스킹을 거친 내용만 GPT로 전달됩니다.
 */
@Entity
@Table(name = "FILE_ASSET")
public class FileAsset {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
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
