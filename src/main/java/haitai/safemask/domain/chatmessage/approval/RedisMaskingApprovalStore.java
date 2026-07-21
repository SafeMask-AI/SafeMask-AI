package haitai.safemask.domain.chatmessage.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** Redis TTL과 Lua 원자 연산으로 최신 1건·1회 사용 정책을 보장합니다. */
@Component
@RequiredArgsConstructor
public class RedisMaskingApprovalStore implements MaskingApprovalStore {

	private static final String KEY_PREFIX = "safemask:masking-approval:";
	private static final RedisScript<String> SAVE_SCRIPT = RedisScript.of("""
		redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[2])
		redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[1])
		return ARGV[1]
		""", String.class);
	private static final RedisScript<String> CONSUME_SCRIPT = RedisScript.of("""
		if redis.call('GET', KEYS[2]) ~= ARGV[1] then
		  redis.call('DEL', KEYS[1])
		  return nil
		end
		local value = redis.call('GET', KEYS[1])
		if not value then return nil end
		redis.call('DEL', KEYS[1])
		redis.call('DEL', KEYS[2])
		return value
		""", String.class);
	private static final RedisScript<Long> DELETE_SCRIPT = RedisScript.of("""
		redis.call('DEL', KEYS[1])
		if redis.call('GET', KEYS[2]) == ARGV[1] then
		  redis.call('DEL', KEYS[2])
		end
		return 1
		""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	@Override
	public String save(MaskingApprovalSnapshot snapshot, Duration ttl) {
		String approvalId = UUID.randomUUID().toString();
		String json = serialize(snapshot);
		long ttlMillis = Math.max(1, ttl.toMillis());
		redisTemplate.execute(SAVE_SCRIPT,
			List.of(snapshotKey(snapshot.memberId(), approvalId), roomPointerKey(snapshot.memberId(), snapshot.chatRoomId())),
			approvalId, json, String.valueOf(ttlMillis));
		return approvalId;
	}

	@Override
	public Optional<MaskingApprovalSnapshot> find(Long memberId, String approvalId) {
		if (!isValidApprovalId(approvalId)) {
			return Optional.empty();
		}
		String json = redisTemplate.opsForValue().get(snapshotKey(memberId, approvalId));
		if (json == null) {
			return Optional.empty();
		}
		MaskingApprovalSnapshot snapshot = deserialize(json);
		String latestId = redisTemplate.opsForValue().get(roomPointerKey(memberId, snapshot.chatRoomId()));
		if (!approvalId.equals(latestId) || !memberId.equals(snapshot.memberId())) {
			return Optional.empty();
		}
		return Optional.of(snapshot);
	}

	@Override
	public Optional<MaskingApprovalSnapshot> consume(Long memberId, String approvalId) {
		Optional<MaskingApprovalSnapshot> found = find(memberId, approvalId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		MaskingApprovalSnapshot snapshot = found.get();
		String json = redisTemplate.execute(CONSUME_SCRIPT,
			List.of(snapshotKey(memberId, approvalId), roomPointerKey(memberId, snapshot.chatRoomId())), approvalId);
		return json == null ? Optional.empty() : Optional.of(deserialize(json));
	}

	@Override
	public void delete(Long memberId, String approvalId) {
		find(memberId, approvalId).ifPresent(snapshot -> redisTemplate.execute(DELETE_SCRIPT,
			List.of(snapshotKey(memberId, approvalId), roomPointerKey(memberId, snapshot.chatRoomId())), approvalId));
	}

	@Override
	public void deleteForRoom(Long memberId, Long chatRoomId) {
		String pointerKey = roomPointerKey(memberId, chatRoomId);
		String approvalId = redisTemplate.opsForValue().get(pointerKey);
		if (approvalId != null && isValidApprovalId(approvalId)) {
			redisTemplate.delete(List.of(snapshotKey(memberId, approvalId), pointerKey));
		} else {
			redisTemplate.delete(pointerKey);
		}
	}

	private String serialize(MaskingApprovalSnapshot snapshot) {
		try {
			return objectMapper.writeValueAsString(snapshot);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize masking approval snapshot.", e);
		}
	}

	private MaskingApprovalSnapshot deserialize(String json) {
		try {
			return objectMapper.readValue(json, MaskingApprovalSnapshot.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to deserialize masking approval snapshot.", e);
		}
	}

	private boolean isValidApprovalId(String approvalId) {
		try {
			UUID.fromString(approvalId);
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private String snapshotKey(Long memberId, String approvalId) {
		return memberPrefix(memberId) + "snapshot:" + approvalId;
	}

	private String roomPointerKey(Long memberId, Long chatRoomId) {
		return memberPrefix(memberId) + "room:" + chatRoomId;
	}

	/** 같은 회원의 승인 키를 Redis Cluster에서도 동일 슬롯에 배치합니다. */
	private String memberPrefix(Long memberId) {
		return KEY_PREFIX + "{" + memberId + "}:";
	}
}
