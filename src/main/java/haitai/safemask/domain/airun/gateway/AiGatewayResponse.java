package haitai.safemask.domain.airun.gateway;

import java.util.List;

public record AiGatewayResponse(
	String maskedText,
	String model,
	Integer inputTokens,
	Integer outputTokens,
	List<WebSource> sources
) {
}
