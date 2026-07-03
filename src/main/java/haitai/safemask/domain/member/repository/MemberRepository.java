package haitai.safemask.domain.member.repository;

import haitai.safemask.domain.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

	/** 로그인 시 사번(loginId)으로 회원을 조회합니다. */
	Optional<Member> findByLoginId(String loginId);

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
