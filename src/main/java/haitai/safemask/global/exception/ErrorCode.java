package haitai.safemask.global.exception;

import org.springframework.http.HttpStatus;

/**
 * 서비스 전체에서 사용하는 에러 코드를 한 곳에서 관리합니다.
 *
 * Thymeleaf 화면에서는 사용자에게 보여줄 메시지가 필요하고,
 * API 요청에서는 HTTP 상태 코드와 에러 코드가 필요합니다.
 * ErrorCode는 두 흐름에서 같은 기준을 사용하기 위한 공통 정의입니다.
 */
public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "COMMON_400", "잘못된 요청입니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "COMMON_401", "로그인이 필요합니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON_403", "접근 권한이 없습니다."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON_404", "요청한 대상을 찾을 수 없습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 내부 오류가 발생했습니다."),
	AI_SERVICE_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "AI_502_1", "AI 응답을 생성하지 못했습니다. 잠시 후 다시 시도해 주세요."),

	// ===== 인증(Auth) =====
	// 로그인 실패 시 사번 존재 여부와 비밀번호 오류를 구분해서 알려주면
	// 공격자가 가입된 사번을 추측할 수 있으므로 하나의 메시지로 통일합니다.
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "AUTH_401_1", "사번 또는 비밀번호가 올바르지 않습니다."),
	INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_2", "유효하지 않은 토큰입니다."),
	EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_3", "만료된 토큰입니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_4", "유효하지 않은 리프레시 토큰입니다. 다시 로그인해 주세요."),
	EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "AUTH_401_5", "만료된 리프레시 토큰입니다. 다시 로그인해 주세요."),
	DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "AUTH_409_1", "이미 사용 중인 사번입니다."),
	DUPLICATE_EMAIL(HttpStatus.CONFLICT, "AUTH_409_2", "이미 사용 중인 이메일입니다.");

	private final HttpStatus status;
	private final String code;
	private final String message;

	ErrorCode(HttpStatus status, String code, String message) {
		this.status = status;
		this.code = code;
		this.message = message;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getMessage() {
		return message;
	}
}
