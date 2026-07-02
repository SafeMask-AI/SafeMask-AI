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
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON_500", "서버 내부 오류가 발생했습니다.");

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
