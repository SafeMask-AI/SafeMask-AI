package haitai.safemask.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 전역 JSON 직렬화/역직렬화 설정입니다.
 *
 * <p>컨트롤러가 ObjectMapper를 직접 생성하지 않고 같은 Bean을 주입받게 하여,
 * API 요청 본문과 multipart 보조 JSON(manualMasks)이 동일한 Jackson 설정을 사용합니다.
 */
@Configuration
public class JacksonConfig {

	@Bean
	public ObjectMapper objectMapper() {
		return JsonMapper.builder()
			.findAndAddModules()
			.build();
	}
}
