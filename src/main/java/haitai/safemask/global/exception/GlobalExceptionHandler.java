package haitai.safemask.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * 서비스 전역 예외를 한 곳에서 처리하는 클래스입니다.
 *
 * SafeMask는 Thymeleaf 화면을 렌더링하는 요청과 JSON을 주고받는 요청이 함께 존재할 수 있습니다.
 * 그래서 예외가 발생했을 때 요청 성격을 확인한 뒤,
 * 화면 요청이면 에러 페이지를 반환하고 API 요청이면 JSON 응답을 반환합니다.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

	private static final String ERROR_VIEW = "error/error";

	/**
	 * 서비스에서 의도적으로 발생시킨 비즈니스 예외를 처리합니다.
	 *
	 * 예를 들어 사용자 조회 실패, 권한 부족, 잘못된 마스킹 요청처럼
	 * 개발자가 예상 가능한 예외는 CustomException으로 감싸서 던집니다.
	 */
	@ExceptionHandler(CustomException.class)
	public Object handleCustomException(CustomException exception, HttpServletRequest request, Model model) {
		ErrorCode errorCode = exception.getErrorCode();
		String message = exception.getMessage();

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
		}

		addErrorAttributes(model, errorCode, message, request.getRequestURI());
		return ERROR_VIEW;
	}

	/**
	 * @Valid 검증 실패 예외를 처리합니다.
	 *
	 * 폼 입력이나 요청 DTO 검증에서 실패한 첫 번째 필드 메시지를 사용자에게 보여줍니다.
	 * 구체적인 필드 메시지가 없으면 공통 잘못된 요청 메시지를 사용합니다.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public Object handleValidationException(
		MethodArgumentNotValidException exception,
		HttpServletRequest request,
		Model model
	) {
		ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
		String message = exception.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(FieldError::getDefaultMessage)
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(errorCode.getMessage());

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
		}

		addErrorAttributes(model, errorCode, message, request.getRequestURI());
		return ERROR_VIEW;
	}

	/**
	 * @RequestParam, @PathVariable 등 메서드 파라미터 검증 실패 예외를 처리합니다.
	 *
	 * @Valid가 붙은 DTO(요청 본문) 검증 실패는 MethodArgumentNotValidException이지만,
	 * 쿼리 파라미터에 직접 붙인 @NotBlank, @Email 등의 검증 실패는
	 * HandlerMethodValidationException(또는 ConstraintViolationException)으로 던져집니다.
	 * 이 핸들러가 없으면 500으로 떨어지므로 400 + 필드 메시지로 변환합니다.
	 * (예: 회원가입 이메일 중복 확인에서 잘못된 이메일 형식을 보낸 경우)
	 */
	@ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
	public Object handleParameterValidationException(
		Exception exception,
		HttpServletRequest request,
		Model model
	) {
		ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
		String message = resolveParameterMessage(exception, errorCode);

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
		}

		addErrorAttributes(model, errorCode, message, request.getRequestURI());
		return ERROR_VIEW;
	}

	/**
	 * 별도로 처리하지 못한 예외를 마지막에 잡습니다.
	 *
	 * 예상하지 못한 예외의 상세 내용은 사용자에게 그대로 노출하지 않습니다.
	 * DB 정보, 파일 경로, 내부 클래스명 등이 화면이나 JSON에 노출될 수 있기 때문입니다.
	 */
	@ExceptionHandler(Exception.class)
	public Object handleUnexpectedException(Exception exception, HttpServletRequest request, Model model) {
		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, errorCode.getMessage(), request.getRequestURI()));
		}

		addErrorAttributes(model, errorCode, errorCode.getMessage(), request.getRequestURI());
		return ERROR_VIEW;
	}

	/**
	 * 요청이 화면 렌더링용인지 JSON 응답용인지 판단합니다.
	 *
	 * /api로 시작하는 요청은 JSON 응답을 우선하고,
	 * Accept 헤더에 application/json이 포함된 요청도 API 요청으로 간주합니다.
	 */
	private boolean isApiRequest(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String accept = request.getHeader("Accept");

		return uri.startsWith("/api") || (accept != null && accept.contains("application/json"));
	}

	/** 파라미터 검증 예외에서 첫 번째 위반 메시지를 꺼냅니다. 없으면 공통 메시지를 사용합니다. */
	private String resolveParameterMessage(Exception exception, ErrorCode errorCode) {
		if (exception instanceof HandlerMethodValidationException validationException) {
			return validationException.getAllErrors()
				.stream()
				.map(MessageSourceResolvable::getDefaultMessage)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(errorCode.getMessage());
		}
		if (exception instanceof ConstraintViolationException violationException) {
			return violationException.getConstraintViolations()
				.stream()
				.map(ConstraintViolation::getMessage)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(errorCode.getMessage());
		}
		return errorCode.getMessage();
	}

	private void addErrorAttributes(Model model, ErrorCode errorCode, String message, String path) {
		model.addAttribute("status", errorCode.getStatus().value());
		model.addAttribute("code", errorCode.getCode());
		model.addAttribute("message", message);
		model.addAttribute("path", path);
	}
}
