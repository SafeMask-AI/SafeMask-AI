package haitai.safemask.domain.airun.repository;

import haitai.safemask.domain.airun.entity.AiRun;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiRunRepository extends JpaRepository<AiRun, Long> {

	/** 취소와 완료가 동시에 상태를 덮어쓰지 않도록 실행 행을 쓰기 잠금으로 조회합니다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select run from AiRun run where run.id = :id")
	Optional<AiRun> findByIdForUpdate(@Param("id") Long id);
}
