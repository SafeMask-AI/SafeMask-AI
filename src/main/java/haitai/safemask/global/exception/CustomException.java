package haitai.safemask.global.exception;

/**
 * 서비스에서 의도적으로 발생시키는 비즈니스 예외의 공통 부모 클래스입니다.
 *
 * 서비스 계층에서 예외 상황이 발생했을 때 문자열 메시지만 직접 던지면
 * 화면 처리, API 응답 처리, HTTP 상태 코드 관리가 여러 곳으로 흩어지기 쉽습니다.
 * 따라서 비즈니스 예외는 ErrorCode를 함께 가지도록 하여
 * GlobalExceptionHandler에서 일관된 방식으로 처리합니다.
 */
public class CustomException extends RuntimeException {

	private final ErrorCode errorCode;

	public CustomException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}

	public CustomException(ErrorCode errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public CustomException(ErrorCode errorCode, Throwable cause) {
		super(errorCode.getMessage(), cause);
		this.errorCode = errorCode;
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}
}
