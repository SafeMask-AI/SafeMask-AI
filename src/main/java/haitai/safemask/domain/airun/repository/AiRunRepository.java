package haitai.safemask.domain.airun.repository;

import haitai.safemask.domain.airun.entity.AiRun;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiRunRepository extends JpaRepository<AiRun, Long> {

	interface MonitoringSummaryProjection {
		long getRequestCount();
		long getCompletedCount();
		long getFailedCount();
		long getCancelledCount();
		Double getAverageResponseMillis();
	}

	interface DailyMonitoringProjection {
		String getDayKey();
		long getRequestCount();
		long getFailedCount();
	}

	interface LatestRunProjection {
		String getStatus();
		LocalDateTime getUpdatedAt();
	}

	@Query(value = """
		SELECT COUNT(*) AS requestCount,
		       COALESCE(SUM(CASE WHEN run.STATUS = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS completedCount,
		       COALESCE(SUM(CASE WHEN run.STATUS = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCount,
		       COALESCE(SUM(CASE WHEN run.STATUS = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelledCount,
		       AVG(CASE
		           WHEN run.STATUS = 'COMPLETED'
		                AND run.REQUESTED_AT IS NOT NULL AND run.COMPLETED_AT IS NOT NULL
		           THEN (CAST(run.COMPLETED_AT AS DATE) - CAST(run.REQUESTED_AT AS DATE)) * 86400000
		       END) AS averageResponseMillis
		FROM MASK_AI_RUN run
		WHERE run.CREATED_AT >= :since
		""", nativeQuery = true)
	MonitoringSummaryProjection summarizeSince(@Param("since") LocalDateTime since);

	@Query(value = """
		SELECT TO_CHAR(run.CREATED_AT, 'YYYY-MM-DD') AS dayKey,
		       COUNT(*) AS requestCount,
		       COALESCE(SUM(CASE WHEN run.STATUS = 'FAILED' THEN 1 ELSE 0 END), 0) AS failedCount
		FROM MASK_AI_RUN run
		WHERE run.CREATED_AT >= :since
		GROUP BY TO_CHAR(run.CREATED_AT, 'YYYY-MM-DD')
		ORDER BY dayKey
		""", nativeQuery = true)
	List<DailyMonitoringProjection> summarizeDailySince(@Param("since") LocalDateTime since);

	/** 운영 Oracle의 구버전 호환을 위해 FETCH FIRST 대신 ROWNUM 외부 쿼리를 사용합니다. */
	@Query(value = """
		SELECT latest.STATUS AS status, latest.UPDATED_AT AS updatedAt
		FROM (
		    SELECT run.STATUS, run.UPDATED_AT
		    FROM MASK_AI_RUN run
		    ORDER BY run.UPDATED_AT DESC
		) latest
		WHERE ROWNUM <= 1
		""", nativeQuery = true)
	List<LatestRunProjection> findLatestForMonitoring();

	/** 취소와 완료가 동시에 상태를 덮어쓰지 않도록 실행 행을 쓰기 잠금으로 조회합니다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select run from AiRun run where run.id = :id")
	Optional<AiRun> findByIdForUpdate(@Param("id") Long id);
}
