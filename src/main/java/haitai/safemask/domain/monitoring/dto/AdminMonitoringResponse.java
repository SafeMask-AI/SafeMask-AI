package haitai.safemask.domain.monitoring.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 관리자 화면에는 원문·사용자·파일명 없이 집계된 운영 지표만 전달합니다. */
public record AdminMonitoringResponse(
	LocalDateTime generatedAt,
	TodaySummary today,
	List<DailyMetric> trend,
	List<MaskingTypeMetric> maskingTypes,
	List<OutcomeMetric> outcomes,
	List<SystemHealth> systems
) {
	public record TodaySummary(
		long requestCount,
		long maskedRequestCount,
		long detectionCount,
		long completedCount,
		long failedCount,
		long cancelledCount,
		double maskingRate,
		double successRate,
		long averageResponseMillis
	) {
	}

	public record DailyMetric(
		String date,
		long requestCount,
		long maskedRequestCount,
		long detectionCount,
		long failedCount
	) {
	}

	public record MaskingTypeMetric(String type, String label, long count, double rate) {
	}

	public record OutcomeMetric(String status, String label, long count) {
	}

	public record SystemHealth(String key, String label, String status, String message) {
	}
}
