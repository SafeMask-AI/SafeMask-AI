package haitai.safemask.domain.masking.engine;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * 탐지된 원본값에 마스킹 토큰을 발급하는 함수형 인터페이스입니다.
 *
 * <p>엔진(MaskingEngine)이 저장소 구현에 직접 의존하지 않도록 분리한 접점으로,
 * 운영에서는 Redis 기반 저장소(같은 방에서 같은 값 → 항상 같은 토큰)를,
 * 테스트에서는 인메모리 구현을 주입합니다.
 */
@FunctionalInterface
public interface TokenAssigner {

	/**
	 * 원본값에 대응하는 토큰을 반환합니다.
	 * 같은 (type, value)에는 반드시 같은 토큰을 반환해야
	 * GPT가 대화 맥락에서 동일 대상을 일관되게 인식할 수 있습니다.
	 *
	 * @return "[TYPE_001]" 형태의 토큰 (예: [PHONE_001])
	 */
	String assign(MaskingType type, String value);

	/**
	 * 엔진이 직접 만든 토큰을 저장소에 등록합니다.
	 * SQL 의미 보존형 토큰처럼 "[SQL_001]" 채번 규칙을 쓰지 않는 경우에도
	 * GPT 응답 원복을 위해 token -> 원본값 매핑은 필요합니다.
	 */
	default void remember(MaskingType type, String value, String token) {
	}
}
