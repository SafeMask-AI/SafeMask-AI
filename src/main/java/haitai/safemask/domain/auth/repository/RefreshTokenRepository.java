package haitai.safemask.domain.auth.repository;

import haitai.safemask.domain.auth.entity.RefreshToken;
import haitai.safemask.domain.member.entity.Member;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	/** 토큰 갱신/로그아웃 시 클라이언트가 보낸 토큰 값으로 조회합니다. */
	Optional<RefreshToken> findByToken(String token);

	/**
	 * 회원의 기존 Refresh Token을 모두 삭제합니다.
	 * 새 토큰 발급(rotation) 전에 호출하여 회원당 유효 토큰이 1개만 존재하도록 합니다.
	 */
	void deleteByMember(Member member);
}
