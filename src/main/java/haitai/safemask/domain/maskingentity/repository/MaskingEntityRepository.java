package haitai.safemask.domain.maskingentity.repository;

import haitai.safemask.domain.maskingentity.entity.MaskingEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MaskingEntityRepository extends JpaRepository<MaskingEntity, Long> {

	interface MonitoringSummaryProjection {
		long getMaskedRequestCount();
		long getDetectionCount();
	}

	interface DailyMonitoringProjection {
		String getDayKey();
		long getMaskedRequestCount();
		long getDetectionCount();
	}

	interface TypeMonitoringProjection {
		String getType();
		long getDetectionCount();
	}

	@Query(value = """
		SELECT COUNT(DISTINCT entity.AI_RUN_ID) AS maskedRequestCount,
		       COUNT(*) AS detectionCount
		FROM MASK_MASKING_ENTITY entity
		JOIN MASK_AI_RUN run ON run.ID = entity.AI_RUN_ID
		WHERE run.CREATED_AT >= :since
		""", nativeQuery = true)
	MonitoringSummaryProjection summarizeSince(@Param("since") LocalDateTime since);

	@Query(value = """
		SELECT TO_CHAR(run.CREATED_AT, 'YYYY-MM-DD') AS dayKey,
		       COUNT(DISTINCT entity.AI_RUN_ID) AS maskedRequestCount,
		       COUNT(*) AS detectionCount
		FROM MASK_MASKING_ENTITY entity
		JOIN MASK_AI_RUN run ON run.ID = entity.AI_RUN_ID
		WHERE run.CREATED_AT >= :since
		GROUP BY TO_CHAR(run.CREATED_AT, 'YYYY-MM-DD')
		ORDER BY dayKey
		""", nativeQuery = true)
	List<DailyMonitoringProjection> summarizeDailySince(@Param("since") LocalDateTime since);

	@Query(value = """
		SELECT entity.TYPE AS type, COUNT(*) AS detectionCount
		FROM MASK_MASKING_ENTITY entity
		JOIN MASK_AI_RUN run ON run.ID = entity.AI_RUN_ID
		WHERE run.CREATED_AT >= :since
		GROUP BY entity.TYPE
		ORDER BY detectionCount DESC, entity.TYPE
		""", nativeQuery = true)
	List<TypeMonitoringProjection> summarizeTypesSince(@Param("since") LocalDateTime since);
}
