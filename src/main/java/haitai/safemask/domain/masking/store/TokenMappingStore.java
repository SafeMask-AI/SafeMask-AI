package haitai.safemask.domain.masking.store;

import haitai.safemask.domain.maskingentity.enums.MaskingType;

/**
 * "원본값 ↔ 마스킹 토큰" 매핑을 채팅방 단위로 보관하는 저장소입니다.
 *
	 * <p>이 저장소는 AI 응답 원복에 필요한 값-토큰 대응 관계만 Redis에 임시 보관합니다.
	 * 최종 전송된 사용자 메시지와 첨부 원본은 사내 DB·파일 저장소의 별도 보존 정책을
	 * 따르며, 이 매핑 자체는 DB에 중복 저장하지 않습니다.
 */
public interface TokenMappingStore {

	/**
	 * 원본값에 대응하는 토큰을 조회하고, 없으면 새로 발급합니다.
	 *
	 * <p>같은 방에서 같은 (type, value)는 항상 같은 토큰을 반환합니다.
	 * 덕분에 이전 대화를 GPT에 다시 보낼 때도 동일 대상이 동일 토큰으로 표현되어
	 * GPT가 "[PERSON_001]이 아까 그 사람"임을 맥락으로 이해할 수 있습니다.
	 *
	 * @return "[TYPE_001]" 형태의 토큰
	 */
	String getOrCreateToken(Long chatRoomId, MaskingType type, String value);

	/**
	 * 외부에서 생성한 토큰을 같은 방의 원복 매핑에 저장합니다.
	 *
	 * <p>SQL 의미 보존형 토큰(T01_ORDER, C.CUSTOMER_PHONE 등)은 엔진이 직접 생성하므로
	 * 일반 순번 채번을 거치지 않습니다. 그래도 GPT 응답에서 해당 토큰이 돌아오면
	 * 원본 SQL 식별자로 복원할 수 있어야 하므로 같은 Redis 매핑에 등록합니다.
	 */
	void rememberToken(Long chatRoomId, MaskingType type, String value, String token);

	/**
	 * 토큰의 원본값을 조회합니다. (GPT 응답 원복용)
	 *
	 * @return 원본값. 매핑이 없으면(TTL 만료 등) null
	 */
	String findOriginal(Long chatRoomId, String token);

	/**
	 * 채팅방의 모든 매핑을 즉시 삭제합니다.
	 * 방 삭제 시 TTL 만료를 기다리지 않고 원본값을 바로 파기하기 위해 사용합니다.
	 */
	void deleteMappings(Long chatRoomId);
}
