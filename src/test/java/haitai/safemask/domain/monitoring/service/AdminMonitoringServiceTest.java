package haitai.safemask.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import haitai.safemask.domain.airun.repository.AiRunRepository;
import haitai.safemask.domain.fileasset.service.FileStorageService;
import haitai.safemask.domain.maskingentity.repository.MaskingEntityRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

class AdminMonitoringServiceTest {

	private final AiRunRepository aiRunRepository = mock(AiRunRepository.class);
	private final MaskingEntityRepository maskingEntityRepository = mock(MaskingEntityRepository.class);
	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
	private final FileStorageService fileStorageService = mock(FileStorageService.class);
	private final RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
	private final RedisConnection redisConnection = mock(RedisConnection.class);
	private AdminMonitoringService service;

	@BeforeEach
	void setUp() throws Exception {
		service = new AdminMonitoringService(
			aiRunRepository, maskingEntityRepository, redisTemplate, fileStorageService);
		when(redisTemplate.getConnectionFactory()).thenReturn(redisConnectionFactory);
		when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
		when(redisConnection.ping()).thenReturn("PONG");
		when(fileStorageService.inspectStatus())
			.thenReturn(new FileStorageService.StorageStatus(true, 20L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024));
	}

	@Test
	void returnsOnlyAggregatedProtectionAndOperationMetrics() {
		AiRunRepository.MonitoringSummaryProjection runs = mock(AiRunRepository.MonitoringSummaryProjection.class);
		when(runs.getRequestCount()).thenReturn(10L);
		when(runs.getCompletedCount()).thenReturn(8L);
		when(runs.getFailedCount()).thenReturn(2L);
		when(runs.getCancelledCount()).thenReturn(1L);
		when(runs.getAverageResponseMillis()).thenReturn(2450.0);
		when(aiRunRepository.summarizeSince(any())).thenReturn(runs);
		when(aiRunRepository.summarizeDailySince(any())).thenReturn(List.of());
		AiRunRepository.LatestRunProjection latest = latestRun("COMPLETED");
		when(aiRunRepository.findLatestForMonitoring()).thenReturn(List.of(latest));

		MaskingEntityRepository.MonitoringSummaryProjection masking =
			mock(MaskingEntityRepository.MonitoringSummaryProjection.class);
		when(masking.getMaskedRequestCount()).thenReturn(6L);
		when(masking.getDetectionCount()).thenReturn(21L);
		when(maskingEntityRepository.summarizeSince(any())).thenReturn(masking);
		when(maskingEntityRepository.summarizeDailySince(any())).thenReturn(List.of());
		MaskingEntityRepository.TypeMonitoringProjection phone = maskingType("PHONE", 12L);
		when(maskingEntityRepository.summarizeTypesSince(any())).thenReturn(List.of(phone));

		var response = service.dashboard();

		assertThat(response.today().requestCount()).isEqualTo(10);
		assertThat(response.today().maskedRequestCount()).isEqualTo(6);
		assertThat(response.today().detectionCount()).isEqualTo(21);
		assertThat(response.today().maskingRate()).isEqualTo(60.0);
		assertThat(response.today().successRate()).isEqualTo(80.0);
		assertThat(response.today().averageResponseMillis()).isEqualTo(2450);
		assertThat(response.maskingTypes()).singleElement().satisfies(metric -> {
			assertThat(metric.label()).isEqualTo("전화번호");
			assertThat(metric.count()).isEqualTo(12);
		});
		assertThat(response.trend()).hasSize(7);
		assertThat(response.systems()).extracting("status")
			.containsExactly("NORMAL", "NORMAL", "NORMAL", "NORMAL");
	}

	@Test
	void databaseFailureReturnsSafeStatusWithoutInternalErrorDetails() {
		when(aiRunRepository.summarizeSince(any())).thenThrow(new IllegalStateException("ORA-00942 SECRET_TABLE"));

		var response = service.dashboard();

		assertThat(response.today().requestCount()).isZero();
		assertThat(response.systems()).filteredOn(system -> system.key().equals("database"))
			.singleElement().satisfies(system -> {
				assertThat(system.status()).isEqualTo("ERROR");
				assertThat(system.message()).doesNotContain("ORA", "SECRET_TABLE");
			});
		assertThat(response.systems()).filteredOn(system -> system.key().equals("ai"))
			.singleElement().extracting("status").isEqualTo("ERROR");
	}

	private AiRunRepository.LatestRunProjection latestRun(String status) {
		AiRunRepository.LatestRunProjection projection = mock(AiRunRepository.LatestRunProjection.class);
		when(projection.getStatus()).thenReturn(status);
		return projection;
	}

	private MaskingEntityRepository.TypeMonitoringProjection maskingType(String type, long count) {
		MaskingEntityRepository.TypeMonitoringProjection projection =
			mock(MaskingEntityRepository.TypeMonitoringProjection.class);
		when(projection.getType()).thenReturn(type);
		when(projection.getDetectionCount()).thenReturn(count);
		return projection;
	}
}
