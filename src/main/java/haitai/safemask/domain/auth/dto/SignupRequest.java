package haitai.safemask.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 최종 단계 요청 DTO입니다.
 *
 * 프론트에서는 단계별(사번 → 이메일 → 이름/부서 → 비밀번호)로 입력을 받지만,
 * 실제 가입은 모든 값을 모아 이 요청 한 번으로 처리합니다.
 * 중간 단계의 중복 확인은 check-login-id / check-email API를 사용합니다.
 */
public record SignupRequest(

	@NotBlank(message = "사번은 필수입니다.")
	@Size(max = 50, message = "사번은 50자 이하여야 합니다.")
	String loginId,

	@NotBlank(message = "비밀번호는 필수입니다.")
	// 최소 8자, 영문자와 숫자를 각각 1자 이상 포함하도록 강제합니다.
	@Pattern(
		regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
		message = "비밀번호는 8자 이상, 영문자와 숫자를 포함해야 합니다."
	)
	String password,

	@NotBlank(message = "이름은 필수입니다.")
	@Size(max = 50, message = "이름은 50자 이하여야 합니다.")
	String name,

	@NotBlank(message = "이메일은 필수입니다.")
	@Email(message = "올바른 이메일 형식이 아닙니다.")
	@Size(max = 100, message = "이메일은 100자 이하여야 합니다.")
	String email,

	@NotBlank(message = "부서는 필수입니다.")
	@Size(max = 100, message = "부서명은 100자 이하여야 합니다.")
	String department
) {
}
