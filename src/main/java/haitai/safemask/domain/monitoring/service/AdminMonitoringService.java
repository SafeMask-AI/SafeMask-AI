package haitai.safemask.domain.monitoring.service;

import haitai.safemask.domain.airun.enums.AiRunStatus;
import haitai.safemask.domain.airun.repository.AiRunRepository;
import haitai.safemask.domain.fileasset.service.FileStorageService;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import haitai.safemask.domain.maskingentity.repository.MaskingEntityRepository;
import haitai.safemask.domain.monitoring.dto.AdminMonitoringResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개별 직원의 질문을 열람하지 않고 SafeMask의 보호 실적과 운영 상태만 집계합니다.
 * 조회 범위는 오늘과 최근 7일로 제한해 운영 DB의 채팅 처리에 주는 부담을 제한합니다.
 */
@Service
@Transactional(readOnly = true)
@Slf4j
public class AdminMonitoringService {

	private static final int TREND_DAYS = 7;

	private final AiRunRepository aiRunRepository;
	private final MaskingEntityRepository maskingEntityRepository;
	private final StringRedisTemplate redisTemplate;
	private final FileStorageService fileStorageService;

	public AdminMonitoringService(AiRunRepository aiRunRepository,
		MaskingEntityRepository maskingEntityRepository,
		StringRedisTemplate redisTemplate,
		FileStorageService fileStorageService) {
		this.aiRunRepository = aiRunRepository;
		this.maskingEntityRepository = maskingEntityRepository;
		this.redisTemplate = redisTemplate;
		this.fileStorageService = fileStorageService;
	}

	public AdminMonitoringResponse dashboard() {
		LocalDate today = LocalDate.now();
		LocalDateTime todayStart = today.atStartOfDay();
		LocalDateTime trendStart = today.minusDays(TREND_DAYS - 1L).atStartOfDay();

		List<AdminMonitoringResponse.SystemHealth> systems = new ArrayList<>();
		DashboardData data;
		boolean databaseAvailable;
		try {
			data = loadDashboardData(todayStart, trendStart, today);
			databaseAvailable = true;
			systems.add(health("database", "Oracle DB", "NORMAL", "집계 조회 정상"));
		} catch (RuntimeException exception) {
			// SQL·연결 세부정보는 관리자 화면에도 노출하지 않는다.
			log.warn("관리자 운영 지표 집계에 실패했습니다.", exception);
			data = DashboardData.empty(today);
			databaseAvailable = false;
			systems.add(health("database", "Oracle DB", "ERROR", "운영 지표를 불러오지 못했습니다."));
		}

		systems.add(checkRedis());
		systems.add(checkStorage());
		systems.add(databaseAvailable
			? checkAi(data.latestRun())
			: health("ai", "AI 처리", "ERROR", "DB 상태 복구 후 확인할 수 있습니다."));

		return new AdminMonitoringResponse(
			LocalDateTime.now(), data.today(), data.trend(), data.maskingTypes(), data.outcomes(), systems);
	}

	private DashboardData loadDashboardData(LocalDateTime todayStart, LocalDateTime trendStart, LocalDate today) {
		AiRunRepository.MonitoringSummaryProjection runSummary = aiRunRepository.summarizeSince(todayStart);
		MaskingEntityRepository.MonitoringSummaryProjection maskingSummary =
			maskingEntityRepository.summarizeSince(todayStart);

		long requestCount = value(runSummary == null ? 0 : runSummary.getRequestCount());
		long completedCount = value(runSummary == null ? 0 : runSummary.getCompletedCount());
		long failedCount = value(runSummary == null ? 0 : runSummary.getFailedCount());
		long cancelledCount = value(runSummary == null ? 0 : runSummary.getCancelledCount());
		long maskedRequestCount = value(maskingSummary == null ? 0 : maskingSummary.getMaskedRequestCount());
		long detectionCount = value(maskingSummary == null ? 0 : maskingSummary.getDetectionCount());
		long terminalCount = completedCount + failedCount;

		AdminMonitoringResponse.TodaySummary summary = new AdminMonitoringResponse.TodaySummary(
			requestCount, maskedRequestCount, detectionCount, completedCount, failedCount, cancelledCount,
			rate(maskedRequestCount, requestCount), rate(completedCount, terminalCount),
			runSummary == null || runSummary.getAverageResponseMillis() == null
				? 0 : Math.max(0L, Math.round(runSummary.getAverageResponseMillis())));

		Map<String, MutableDailyMetric> daily = initializeDays(today);
		for (AiRunRepository.DailyMonitoringProjection row : aiRunRepository.summarizeDailySince(trendStart)) {
			MutableDailyMetric metric = daily.get(row.getDayKey());
			if (metric != null) {
				metric.requestCount = row.getRequestCount();
				metric.failedCount = row.getFailedCount();
			}
		}
		for (MaskingEntityRepository.DailyMonitoringProjection row
			: maskingEntityRepository.summarizeDailySince(trendStart)) {
			MutableDailyMetric metric = daily.get(row.getDayKey());
			if (metric != null) {
				metric.maskedRequestCount = row.getMaskedRequestCount();
				metric.detectionCount = row.getDetectionCount();
			}
		}

		List<AdminMonitoringResponse.DailyMetric> trend = daily.entrySet().stream()
			.map(entry -> entry.getValue().toResponse(entry.getKey()))
			.toList();
		List<AdminMonitoringResponse.MaskingTypeMetric> types = maskingTypes(trendStart);
		List<AdminMonitoringResponse.OutcomeMetric> outcomes = List.of(
			new AdminMonitoringResponse.OutcomeMetric("COMPLETED", "처리 완료", completedCount),
			new AdminMonitoringResponse.OutcomeMetric("FAILED", "처리 실패", failedCount),
			new AdminMonitoringResponse.OutcomeMetric("CANCELLED", "사용자 취소", cancelledCount));
		AiRunRepository.LatestRunProjection latest = aiRunRepository.findLatestForMonitoring().stream()
			.findFirst().orElse(null);
		return new DashboardData(summary, trend, types, outcomes, latest);
	}

	private List<AdminMonitoringResponse.MaskingTypeMetric> maskingTypes(LocalDateTime since) {
		List<MaskingEntityRepository.TypeMonitoringProjection> rows =
			maskingEntityRepository.summarizeTypesSince(since);
		long total = rows.stream().mapToLong(MaskingEntityRepository.TypeMonitoringProjection::getDetectionCount).sum();
		return rows.stream().map(row -> {
			String type = row.getType();
			String label;
			try {
				label = MaskingType.valueOf(type).getDisplayName();
			} catch (IllegalArgumentException exception) {
				label = "기타 보호 정보";
			}
			return new AdminMonitoringResponse.MaskingTypeMetric(type, label, row.getDetectionCount(),
				rate(row.getDetectionCount(), total));
		}).toList();
	}

	private Map<String, MutableDailyMetric> initializeDays(LocalDate today) {
		Map<String, MutableDailyMetric> metrics = new LinkedHashMap<>();
		for (int offset = TREND_DAYS - 1; offset >= 0; offset--) {
			metrics.put(today.minusDays(offset).toString(), new MutableDailyMetric());
		}
		return metrics;
	}

	private AdminMonitoringResponse.SystemHealth checkRedis() {
		try {
			RedisConnectionFactory factory = redisTemplate.getConnectionFactory();
			if (factory == null) {
				return health("redis", "Redis 마스킹 저장소", "ERROR", "연결 설정을 확인해 주세요.");
			}
			try (RedisConnection connection = factory.getConnection()) {
				String pong = connection.ping();
				if (pong == null || !"PONG".equalsIgnoreCase(pong)) {
					return health("redis", "Redis 마스킹 저장소", "WARNING", "응답 상태를 확인해 주세요.");
				}
			}
			return health("redis", "Redis 마스킹 저장소", "NORMAL", "토큰 저장소 정상");
		} catch (RuntimeException exception) {
			return health("redis", "Redis 마스킹 저장소", "ERROR", "연결할 수 없습니다.");
		}
	}

	private AdminMonitoringResponse.SystemHealth checkStorage() {
		try {
			FileStorageService.StorageStatus storage = fileStorageService.inspectStatus();
			if (!storage.writable()) {
				return health("storage", "파일 저장소", "ERROR", "쓰기 권한을 확인해 주세요.");
			}
			double remainingRate = storage.totalBytes() <= 0 ? 1 : (double) storage.usableBytes() / storage.totalBytes();
			String status = remainingRate < 0.1 ? "WARNING" : "NORMAL";
			String message = "사용 가능 " + humanReadableBytes(storage.usableBytes());
			return health("storage", "파일 저장소", status, message);
		} catch (IOException | RuntimeException exception) {
			return health("storage", "파일 저장소", "ERROR", "저장소 상태를 확인할 수 없습니다.");
		}
	}

	private AdminMonitoringResponse.SystemHealth checkAi(AiRunRepository.LatestRunProjection latest) {
		if (latest == null || latest.getStatus() == null) {
			return health("ai", "AI 처리", "IDLE", "아직 처리 이력이 없습니다.");
		}
		AiRunStatus status;
		try {
			status = AiRunStatus.valueOf(latest.getStatus());
		} catch (IllegalArgumentException exception) {
			return health("ai", "AI 처리", "WARNING", "최근 처리 상태를 확인해 주세요.");
		}
		return switch (status) {
			case COMPLETED -> health("ai", "AI 처리", "NORMAL", "최근 요청 처리 완료");
			case FAILED -> health("ai", "AI 처리", "WARNING", "최근 요청에서 오류가 발생했습니다.");
			case CALLING, APPROVED, PENDING -> health("ai", "AI 처리", "PROCESSING", "요청 처리 중");
			case CANCELLED, REJECTED -> health("ai", "AI 처리", "IDLE", "최근 요청이 사용자에 의해 종료됨");
			case PREVIEW -> health("ai", "AI 처리", "IDLE", "사용자 확인 대기");
		};
	}

	private AdminMonitoringResponse.SystemHealth health(String key, String label, String status, String message) {
		return new AdminMonitoringResponse.SystemHealth(key, label, status, message);
	}

	private long value(long value) {
		return Math.max(0, value);
	}

	private double rate(long numerator, long denominator) {
		return denominator <= 0 ? 0 : Math.round((numerator * 10000.0) / denominator) / 100.0;
	}

	private String humanReadableBytes(long bytes) {
		if (bytes >= 1024L * 1024 * 1024) {
			return String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024));
		}
		return String.format("%.0fMB", bytes / (1024.0 * 1024));
	}

	private record DashboardData(
		AdminMonitoringResponse.TodaySummary today,
		List<AdminMonitoringResponse.DailyMetric> trend,
		List<AdminMonitoringResponse.MaskingTypeMetric> maskingTypes,
		List<AdminMonitoringResponse.OutcomeMetric> outcomes,
		AiRunRepository.LatestRunProjection latestRun
	) {
		private static DashboardData empty(LocalDate today) {
			Map<String, MutableDailyMetric> metrics = new LinkedHashMap<>();
			for (int offset = TREND_DAYS - 1; offset >= 0; offset--) {
				metrics.put(today.minusDays(offset).toString(), new MutableDailyMetric());
			}
			return new DashboardData(
				new AdminMonitoringResponse.TodaySummary(0, 0, 0, 0, 0, 0, 0, 0, 0),
				metrics.entrySet().stream().map(entry -> entry.getValue().toResponse(entry.getKey())).toList(),
				List.of(), List.of(), null);
		}
	}

	private static class MutableDailyMetric {
		private long requestCount;
		private long maskedRequestCount;
		private long detectionCount;
		private long failedCount;

		private AdminMonitoringResponse.DailyMetric toResponse(String date) {
			return new AdminMonitoringResponse.DailyMetric(
				date, requestCount, maskedRequestCount, detectionCount, failedCount);
		}
	}
}
