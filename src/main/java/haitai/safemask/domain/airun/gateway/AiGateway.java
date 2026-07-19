package haitai.safemask.domain.airun.gateway;

import java.util.List;

/**
 * 외부 AI 공급자 호출을 채팅 저장·마스킹 흐름에서 분리하는 경계입니다.
 *
 * <p>이 인터페이스에 전달되는 메시지는 반드시 서버에서 마스킹이 끝난 내용이어야 합니다.
 * 구현체는 원문 DB 엔티티나 Redis 토큰 매핑에 접근하지 않습니다.
 */
public interface AiGateway {

	AiGatewayResponse generate(List<AiPromptMessage> messages, AiProgressListener progressListener);
}
