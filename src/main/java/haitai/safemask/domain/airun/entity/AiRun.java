package haitai.safemask.domain.airun.entity;

import haitai.safemask.domain.airun.enums.AiRunStatus;
import haitai.safemask.domain.chatmessage.entity.ChatMessage;
import haitai.safemask.domain.chatroom.entity.ChatRoom;
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
 * GPT API 호출 1회에 대한 실행 이력입니다.
 * "탐지 → 마스킹 미리보기 → 승인 → GPT 호출 → 원복"의 한 사이클을 추적하며,
 * 어떤 데이터가 언제 어떤 모델로 나갔는지에 대한 감사(audit) 근거가 됩니다.
 */
@Getter
@Entity
@Table(name = "MASK_AI_RUN")
public class AiRun {

	@Id
	@SequenceGenerator(name = "ai_run_seq_gen", sequenceName = "SAFEMASK_AI_RUN_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ai_run_seq_gen")
	private Long id;

	/** 실행이 발생한 채팅방 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chatroom_id", nullable = false)
	private ChatRoom chatRoom;

	/** 이 실행을 트리거한 사용자 메시지 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "request_message_id")
	private ChatMessage requestMessage;

	/** 파이프라인 진행 단계 */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AiRunStatus status;

	/** 호출한 OpenAI 모델명 (예: gpt-5.5) */
	@Column(length = 50)
	private String model;

	/** 요청(프롬프트) 토큰 수 — 비용 집계용 */
	@Column
	private Integer promptTokens;

	/** 응답(완성) 토큰 수 — 비용 집계용 */
	@Column
	private Integer completionTokens;

	/** 실패 시 오류 메시지 */
	@Column(length = 1000)
	private String errorMessage;

	/** GPT API 호출 시각 */
	@Column
	private LocalDateTime requestedAt;

	/** 응답 수신 및 원복 완료 시각 */
	@Column
	private LocalDateTime completedAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	protected AiRun() {
	}

	public static AiRun createPreview(ChatRoom chatRoom) {
		AiRun aiRun = new AiRun();
		aiRun.chatRoom = chatRoom;
		aiRun.status = AiRunStatus.PREVIEW;
		return aiRun;
	}

	public static AiRun createApproved(ChatRoom chatRoom, ChatMessage requestMessage) {
		AiRun aiRun = new AiRun();
		aiRun.chatRoom = chatRoom;
		aiRun.requestMessage = requestMessage;
		aiRun.status = AiRunStatus.APPROVED;
		return aiRun;
	}

	public void markCalling(String model) {
		if (this.status != AiRunStatus.APPROVED) {
			throw new IllegalStateException("Only approved AI runs can start calling.");
		}
		this.status = AiRunStatus.CALLING;
		this.model = model;
		this.requestedAt = LocalDateTime.now();
	}

	public void markCompleted(String model, Integer promptTokens, Integer completionTokens) {
		if (this.status != AiRunStatus.CALLING) {
			throw new IllegalStateException("Only calling AI runs can be completed.");
		}
		this.status = AiRunStatus.COMPLETED;
		this.model = model;
		this.promptTokens = promptTokens;
		this.completionTokens = completionTokens;
		this.completedAt = LocalDateTime.now();
	}

	public void markFailed(String message) {
		// 완료 또는 사용자 취소로 확정된 상태는 뒤늦게 도착한 예외가 덮어쓰지 못하게 한다.
		if (this.status == AiRunStatus.COMPLETED || this.status == AiRunStatus.CANCELLED) {
			return;
		}
		this.status = AiRunStatus.FAILED;
		this.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 1000));
		this.completedAt = LocalDateTime.now();
	}

	public boolean markCancelled() {
		if (this.status != AiRunStatus.APPROVED && this.status != AiRunStatus.CALLING) {
			return false;
		}
		this.status = AiRunStatus.CANCELLED;
		this.errorMessage = null;
		this.completedAt = LocalDateTime.now();
		return true;
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;

		if (this.status == null) {
			this.status = AiRunStatus.PENDING;
		}
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
