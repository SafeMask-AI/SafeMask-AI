package haitai.safemask.domain.masking.engine;

/**
 * 마스킹 토큰을 원본값으로 되돌리는(원복) 함수형 인터페이스입니다.
 *
 * <p>TokenAssigner와 마찬가지로 엔진과 저장소 구현을 분리하기 위한 접점입니다.
 * 운영에서는 Redis의 역방향 매핑(토큰 → 원본값)을 조회합니다.
 */
@FunctionalInterface
public interface TokenResolver {

	/**
	 * 토큰에 대응하는 원본값을 반환합니다.
	 *
	 * @param token "[TYPE_001]" 형태의 토큰
	 * @return 원본값. 매핑이 없으면(TTL 만료, 다른 방의 토큰 등) null —
	 *         이 경우 엔진은 토큰을 치환하지 않고 그대로 둡니다.
	 */
	String resolve(String token);
}
