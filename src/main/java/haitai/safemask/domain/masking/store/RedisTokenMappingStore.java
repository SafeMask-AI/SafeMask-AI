package haitai.safemask.domain.masking.store;

import haitai.safemask.domain.masking.config.MaskingProperties;
import haitai.safemask.domain.maskingentity.enums.MaskingType;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 원본값 ↔ 마스킹 토큰 매핑을 채팅방 단위로 Redis에 보관하는 구현체입니다.
 *
 * <p>키 구조 (모두 채팅방 단위이며 같은 TTL을 공유):
 * <ul>
 *   <li>masking:room:{roomId}:v2t — Hash. "타입:원본값" → 토큰.
 *       같은 값에 항상 같은 토큰을 발급하기 위한 정방향 매핑</li>
 *   <li>masking:room:{roomId}:t2v — Hash. 토큰 → 원본값. GPT 응답 원복용 역방향 매핑</li>
 *   <li>masking:room:{roomId}:seq — Hash. 타입 → 발급 순번. [PERSON_001], [PERSON_002]… 채번용</li>
 * </ul>
 *
 * <p>TTL 정책: 발급/조회 등 모든 접근 시 TTL을 갱신합니다.
 * 방이 활발히 사용되는 동안은 매핑이 유지되고, 대화가 끊기면 만료로 원본값이 소멸합니다.
 * 원본값은 DB 어디에도 없으므로 만료 후에는 해당 토큰을 원복할 방법이 없으며,
 * 이는 "원본은 최소한만 보관한다"는 의도된 동작입니다.
 */
@Component
@RequiredArgsConstructor
public class RedisTokenMappingStore implements TokenMappingStore {

	private static final String KEY_PREFIX = "masking:room:";

	private final StringRedisTemplate redisTemplate;
	private final MaskingProperties maskingProperties;

	@Override
	public String getOrCreateToken(Long chatRoomId, MaskingType type, String value) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String valueToTokenKey = valueToTokenKey(chatRoomId);
		// 같은 문자열이라도 타입이 다르면 다른 토큰이어야 하므로 필드에 타입을 함께 넣습니다.
		String field = type.name() + ":" + value;

		String existing = hashOps.get(valueToTokenKey, field);
		if (existing != null) {
			refreshTtl(chatRoomId);
			return existing;
		}

		// 타입별 순번을 원자적으로 증가시켜 토큰 번호를 채번합니다.
		Long sequence = hashOps.increment(sequenceKey(chatRoomId), type.name(), 1);
		String token = "[" + type.getTokenLabel() + "_" + String.format("%03d", sequence) + "]";

		// 같은 방에 같은 값이 동시에 들어오는 경합에 대비해 putIfAbsent로 선점한 쪽 토큰만 사용합니다.
		// 경합에서 지면 채번한 순번 하나가 버려지지만, 번호는 유일성만 중요하므로 문제 없습니다.
		Boolean won = hashOps.putIfAbsent(valueToTokenKey, field, token);
		if (Boolean.TRUE.equals(won)) {
			hashOps.put(tokenToValueKey(chatRoomId), token, value);
		} else {
			token = hashOps.get(valueToTokenKey, field);
		}

		refreshTtl(chatRoomId);
		return token;
	}

	@Override
	public void rememberToken(Long chatRoomId, MaskingType type, String value, String token) {
		if (value == null || value.isBlank() || token == null || token.isBlank()) {
			return;
		}
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String field = type.name() + ":" + value;
		hashOps.putIfAbsent(valueToTokenKey(chatRoomId), field, token);
		hashOps.putIfAbsent(tokenToValueKey(chatRoomId), token, value);
		refreshTtl(chatRoomId);
	}

	@Override
	public String findOriginal(Long chatRoomId, String token) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String value = hashOps.get(tokenToValueKey(chatRoomId), token);
		if (value != null) {
			refreshTtl(chatRoomId);
		}
		return value;
	}

	@Override
	public void deleteMappings(Long chatRoomId) {
		redisTemplate.delete(List.of(
			valueToTokenKey(chatRoomId),
			tokenToValueKey(chatRoomId),
			sequenceKey(chatRoomId)
		));
	}

	/** 방 활동이 있을 때마다 세 키의 TTL을 함께 연장합니다. (일부만 만료되는 불일치 방지) */
	private void refreshTtl(Long chatRoomId) {
		Duration ttl = maskingProperties.getMappingTtl();
		redisTemplate.expire(valueToTokenKey(chatRoomId), ttl);
		redisTemplate.expire(tokenToValueKey(chatRoomId), ttl);
		redisTemplate.expire(sequenceKey(chatRoomId), ttl);
	}

	private String valueToTokenKey(Long chatRoomId) {
		return KEY_PREFIX + chatRoomId + ":v2t";
	}

	private String tokenToValueKey(Long chatRoomId) {
		return KEY_PREFIX + chatRoomId + ":t2v";
	}

	private String sequenceKey(Long chatRoomId) {
		return KEY_PREFIX + chatRoomId + ":seq";
	}
}
