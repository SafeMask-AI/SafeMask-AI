package haitai.safemask.domain.auth.entity;

import haitai.safemask.domain.member.entity.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Access Token 재발급에 사용하는 Refresh Token입니다.
 *
 * Access Token은 만료가 짧아 탈취 피해가 제한적이지만,
 * Refresh Token은 수명이 길기 때문에 서버(DB)에 저장해 두고
 * 재발급 시마다 새 토큰으로 교체(rotation)하여 재사용 공격을 막습니다.
 * 로그아웃 시에는 이 레코드를 삭제해 토큰을 즉시 무효화합니다.
 */
@Entity
@Table(name = "REFRESH_TOKEN")
public class RefreshToken {

	@Id
	@SequenceGenerator(name = "refresh_token_seq_gen", sequenceName = "SAFEMASK_REFRESH_TOKEN_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "refresh_token_seq_gen")
	private Long id;

	/** 클라이언트에 전달되는 토큰 값 (UUID 36자) */
	@Column(nullable = false, unique = true, length = 36)
	private String token;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "member_id", nullable = false)
	private Member member;

	/** 이 시각이 지나면 재발급에 사용할 수 없습니다. */
	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	protected RefreshToken() {
	}

	public RefreshToken(String token, Member member, LocalDateTime expiresAt) {
		this.token = token;
		this.member = member;
		this.expiresAt = expiresAt;
	}

	@PrePersist
	void prePersist() {
		this.createdAt = LocalDateTime.now();
	}

	public Long getId() {
		return id;
	}

	public String getToken() {
		return token;
	}

	public Member getMember() {
		return member;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	/** 만료 여부를 판단합니다. 만료된 토큰은 즉시 삭제하고 재로그인을 유도합니다. */
	public boolean isExpired() {
		return LocalDateTime.now().isAfter(expiresAt);
	}
}
