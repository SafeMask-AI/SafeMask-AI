package haitai.safemask.global.exception;

import java.time.LocalDateTime;

/**
 * JSON 응답이 필요한 요청에서 사용하는 공통 에러 응답 DTO입니다.
 *
 * 현재 프로젝트는 Thymeleaf 화면 중심이지만, 파일 업로드나 채팅 전송처럼
 * 비동기 요청이 섞일 수 있습니다. 이 객체는 /api 요청 또는 JSON을 기대하는 요청에서
 * 프론트가 동일한 형식으로 에러를 처리할 수 있게 해줍니다.
 */
public record ErrorResponse(
	String code,
	String message,
	int status,
	String path,
	LocalDateTime timestamp
) {

	public static ErrorResponse of(ErrorCode errorCode, String message, String path) {
		return new ErrorResponse(
			errorCode.getCode(),
			message,
			errorCode.getStatus().value(),
			path,
			LocalDateTime.now()
		);
	}
}
