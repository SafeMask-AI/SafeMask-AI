package haitai.safemask.domain.auth.dto;

/**
 * 회원가입 단계별 중복 확인(사번/이메일) 응답 DTO입니다.
 * exists가 true면 이미 사용 중인 값이므로 다음 단계로 진행할 수 없습니다.
 */
public record DuplicateCheckResponse(
	boolean exists
) {
}
