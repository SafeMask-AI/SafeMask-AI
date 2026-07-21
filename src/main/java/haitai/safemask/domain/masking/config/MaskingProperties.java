package haitai.safemask.domain.masking.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.yaml의 safemask.masking 설정값을 바인딩하는 클래스입니다.
 *
 * <p>매핑 TTL은 "원본 민감정보를 Redis에 얼마나 오래 두는가"를 결정하는
 * 보안 정책값이므로 코드에 하드코딩하지 않고 외부 설정으로 분리했습니다.
 * 운영 환경에서는 회사 보안 정책에 맞춰 .env(MASKING_MAPPING_TTL)로 조정합니다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "safemask.masking")
public class MaskingProperties {

	/**
	 * 원본↔토큰 매핑의 Redis 보관 시간. (기본 24시간)
	 * 방에서 마스킹/원복 활동이 있을 때마다 갱신되며,
	 * 만료되면 해당 방의 기존 토큰은 더 이상 원복할 수 없습니다.
	 */
	private Duration mappingTtl = Duration.ofHours(24);

	/**
	 * 사용자가 확인한 미리보기 원문·마스킹 결과를 승인 대기 상태로 보관하는 시간입니다.
	 * 장시간 원문을 중복 보관하지 않으면서 일반적인 검토 시간을 허용하도록 기본 15분으로 둡니다.
	 */
	private Duration approvalTtl = Duration.ofMinutes(15);
}
