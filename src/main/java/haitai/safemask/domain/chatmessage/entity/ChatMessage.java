package haitai.safemask.domain.chatmessage.entity;

import haitai.safemask.domain.chatmessage.enums.MessageRole;
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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 채팅방 안의 개별 메시지입니다.
 * 원본 내용과 마스킹된 내용을 분리 보관하는 것이 핵심입니다.
 *
 * <p>흐름:
 * <ul>
 *   <li>USER 메시지: originalContent = 사용자가 입력한 원문, maskedContent = GPT로 실제 전송된 마스킹본</li>
 *   <li>ASSISTANT 메시지: maskedContent = GPT가 반환한 토큰 포함 응답, originalContent = 토큰을 원본값으로 원복한 최종 표시본</li>
 * </ul>
 * 원본(originalContent)은 절대 사외(GPT API)로 나가지 않습니다.
 */
@Entity
@Table(name = "CHAT_MESSAGE")
public class ChatMessage {

	@Id
	@SequenceGenerator(name = "chat_message_seq_gen", sequenceName = "SAFEMASK_CHAT_MESSAGE_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "chat_message_seq_gen")
	private Long id;

	/** 메시지가 속한 채팅방 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "chatroom_id", nullable = false)
	private ChatRoom chatRoom;

	/** 발화 주체 (USER / ASSISTANT) */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MessageRole role;

	/** 원본 내용 (사내에만 보관, 사용자 화면에 표시되는 텍스트) */
	@Lob
	@Column(nullable = false)
	private String originalContent;

	/** 마스킹된 내용 (GPT API로 실제 송수신된 텍스트) */
	@Lob
	@Column
	private String maskedContent;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected ChatMessage() {
	}

	@PrePersist
	void prePersist() {
		this.createdAt = LocalDateTime.now();
	}
}
