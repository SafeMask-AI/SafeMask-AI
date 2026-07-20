package haitai.safemask.domain.member.entity;

import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.enums.MemberRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * SafeMask에 로그인하는 사용자 정보입니다.
 * 로그인 아이디(사번), 비밀번호, 이름, 이메일, 부서, 권한과 사용 승인 상태를 관리합니다.
 */
@Entity
@Table(name = "MASK_MEMBER")
public class Member {

	@Id
	@SequenceGenerator(name = "member_seq_gen", sequenceName = "SAFEMASK_MEMBER_SEQ", allocationSize = 1)
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_seq_gen")
	private Long id;

	/** 로그인 아이디 = 사번 */
	@Column(nullable = false, unique = true, length = 50)
	private String loginId;

	@Column(nullable = false)
	private String password;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(nullable = false, unique = true, length = 100)
	private String email;

	@Column(length = 100)
	private String department;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberRole role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MemberApprovalStatus approvalStatus;

	/** 마지막 승인 상태를 변경한 관리자 정보의 감사 스냅샷입니다. */
	private Long reviewedByMemberId;

	@Column(length = 50)
	private String reviewedByName;

	private LocalDateTime reviewedAt;

	@Column(nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(nullable = false)
	private LocalDateTime updatedAt;

	protected Member() {
	}

	/**
	 * 회원가입 시 새 Member를 생성합니다.
	 *
	 * 비밀번호는 반드시 인코딩(BCrypt)된 값을 전달해야 합니다.
	 * 평문 비밀번호가 DB에 저장되는 사고를 막기 위해 서비스 계층에서
	 * PasswordEncoder.encode()를 거친 값만 넘기도록 합니다.
	 * role과 approvalStatus는 @PrePersist에서 각각 USER, PENDING으로 채워집니다.
	 */
	public static Member create(String loginId, String encodedPassword, String name, String email,
		String department) {
		Member member = new Member();
		member.loginId = loginId;
		member.password = encodedPassword;
		member.name = name;
		member.email = email;
		member.department = department;
		return member;
	}

	public Long getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	public String getPassword() {
		return password;
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getDepartment() {
		return department;
	}

	public MemberRole getRole() {
		return role;
	}

	public MemberApprovalStatus getApprovalStatus() {
		return approvalStatus;
	}

	public Long getReviewedByMemberId() {
		return reviewedByMemberId;
	}

	public String getReviewedByName() {
		return reviewedByName;
	}

	public LocalDateTime getReviewedAt() {
		return reviewedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public boolean isApproved() {
		return approvalStatus == MemberApprovalStatus.APPROVED;
	}

	/** 관리자 결정과 감사 정보를 하나의 도메인 변경으로 기록합니다. */
	public void review(MemberApprovalStatus status, Member reviewer) {
		if (status == null || status == MemberApprovalStatus.PENDING) {
			throw new IllegalArgumentException("관리자 결정은 APPROVED 또는 REJECTED여야 합니다.");
		}
		this.approvalStatus = status;
		this.reviewedByMemberId = reviewer.getId();
		this.reviewedByName = reviewer.getName();
		this.reviewedAt = LocalDateTime.now();
	}

	/** 관리자가 전혀 없는 초기 설치에서만 서비스가 호출하는 최초 관리자 승격입니다. */
	public void bootstrapAdmin() {
		this.role = MemberRole.ADMIN;
		this.approvalStatus = MemberApprovalStatus.APPROVED;
		this.reviewedByName = "SYSTEM_BOOTSTRAP";
		this.reviewedAt = LocalDateTime.now();
	}

	@PrePersist
	void prePersist() {
		LocalDateTime now = LocalDateTime.now();
		this.createdAt = now;
		this.updatedAt = now;

		if (this.role == null) {
			this.role = MemberRole.USER;
		}
		if (this.approvalStatus == null) {
			this.approvalStatus = MemberApprovalStatus.PENDING;
		}
	}

	@PreUpdate
	void preUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
