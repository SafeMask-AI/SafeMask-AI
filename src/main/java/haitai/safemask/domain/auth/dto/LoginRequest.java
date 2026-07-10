package haitai.safemask.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 로그인 요청 DTO입니다. 사번(loginId)과 비밀번호로 로그인합니다.
 */
public record LoginRequest(

	@NotBlank(message = "사번은 필수입니다.")
	String loginId,

	@NotBlank(message = "비밀번호는 필수입니다.")
	String password,

	Boolean rememberMe
) {
}
