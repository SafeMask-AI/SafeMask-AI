package haitai.safemask.domain.member.repository;

import haitai.safemask.domain.member.entity.Member;
import haitai.safemask.domain.member.enums.MemberApprovalStatus;
import haitai.safemask.domain.member.enums.MemberRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MemberRepository extends JpaRepository<Member, Long> {

	/** 로그인 시 사번(loginId)으로 회원을 조회합니다. */
	Optional<Member> findByLoginId(String loginId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Member m where m.id = :id")
	Optional<Member> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 구버전 Oracle은 Hibernate Pageable이 생성하는 FETCH FIRST 문법을 지원하지 않습니다.
	 * 정렬된 내부 쿼리에 ROWNUM 상·하한을 적용해 DB에서 필요한 페이지만 읽습니다.
	 */
	@Query(value = """
		SELECT page_result.ID, page_result.LOGIN_ID, page_result.PASSWORD,
		       page_result.NAME, page_result.EMAIL, page_result.DEPARTMENT,
		       page_result.ROLE, page_result.APPROVAL_STATUS,
		       page_result.REVIEWED_BY_MEMBER_ID, page_result.REVIEWED_BY_NAME,
		       page_result.REVIEWED_AT, page_result.CREATED_AT, page_result.UPDATED_AT
		FROM (
		    SELECT ordered_result.*, ROWNUM AS PAGE_ROW_NUMBER
		    FROM (
		        SELECT m.ID, m.LOGIN_ID, m.PASSWORD, m.NAME, m.EMAIL, m.DEPARTMENT,
		               m.ROLE, m.APPROVAL_STATUS, m.REVIEWED_BY_MEMBER_ID,
		               m.REVIEWED_BY_NAME, m.REVIEWED_AT, m.CREATED_AT, m.UPDATED_AT
		        FROM MASK_MEMBER m
		        WHERE (:status IS NULL OR m.APPROVAL_STATUS = :status)
		          AND (:keyword IS NULL
		            OR LOWER(m.LOGIN_ID) LIKE LOWER('%' || :keyword || '%')
		            OR LOWER(m.NAME) LIKE LOWER('%' || :keyword || '%')
		            OR LOWER(m.EMAIL) LIKE LOWER('%' || :keyword || '%')
		            OR LOWER(NVL(m.DEPARTMENT, ' ')) LIKE LOWER('%' || :keyword || '%'))
		        ORDER BY m.CREATED_AT DESC
		    ) ordered_result
		    WHERE ROWNUM <= :endRow
		) page_result
		WHERE page_result.PAGE_ROW_NUMBER > :startRow
		""", nativeQuery = true)
	List<Member> searchPage(@Param("status") String status, @Param("keyword") String keyword,
		@Param("startRow") long startRow, @Param("endRow") long endRow);

	@Query(value = """
		SELECT COUNT(*)
		FROM MASK_MEMBER m
		WHERE (:status IS NULL OR m.APPROVAL_STATUS = :status)
		  AND (:keyword IS NULL
		    OR LOWER(m.LOGIN_ID) LIKE LOWER('%' || :keyword || '%')
		    OR LOWER(m.NAME) LIKE LOWER('%' || :keyword || '%')
		    OR LOWER(m.EMAIL) LIKE LOWER('%' || :keyword || '%')
		    OR LOWER(NVL(m.DEPARTMENT, ' ')) LIKE LOWER('%' || :keyword || '%'))
		""", nativeQuery = true)
	long countSearchResults(@Param("status") String status, @Param("keyword") String keyword);

	long countByApprovalStatus(MemberApprovalStatus status);

	long countByRole(MemberRole role);

	/**
	 * 회원가입 1단계: 사번 중복 여부 확인에 사용합니다.
	 *
	 * existsBy를 쓰지 않는 이유: existsBy는 "FETCH FIRST 1 ROWS ONLY" 구문의
	 * SQL을 생성하는데, 이 구문은 Oracle 12c 이상에서만 지원되어
	 * 구버전 Oracle에서 ORA-00933이 발생합니다. countBy는 단순 COUNT(*)라
	 * 모든 버전에서 동작합니다.
	 */
	long countByLoginId(String loginId);

	/** 회원가입 2단계: 이메일 중복 여부 확인에 사용합니다. (countBy 사용 이유는 위와 동일) */
	long countByEmail(String email);
}
