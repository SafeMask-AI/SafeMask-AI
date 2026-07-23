package haitai.safemask.domain.auth.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 인터넷에 공개되는 인증 API의 Redis 기반 요청 제한 정책입니다. */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties(prefix = "safemask.auth.protection")
public class AuthRequestProtectionProperties {

	private boolean enabled = true;

	@Min(1)
	private int loginLimit = 10;

	@NotNull
	private Duration loginWindow = Duration.ofMinutes(5);

	@Min(1)
	private int signupLimit = 5;

	@NotNull
	private Duration signupWindow = Duration.ofHours(1);

	@Min(1)
	private int duplicateCheckLimit = 30;

	@NotNull
	private Duration duplicateCheckWindow = Duration.ofMinutes(1);

	@Min(1)
	private int refreshLimit = 30;

	@NotNull
	private Duration refreshWindow = Duration.ofMinutes(5);
}
