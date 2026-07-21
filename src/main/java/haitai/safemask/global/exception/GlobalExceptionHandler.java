package haitai.safemask.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 서비스 전역 예외를 한 곳에서 처리하는 클래스입니다.
 *
 * SafeMask는 Thymeleaf 화면을 렌더링하는 요청과 JSON을 주고받는 요청이 함께 존재할 수 있습니다.
 * 그래서 예외가 발생했을 때 요청 성격을 확인한 뒤,
 * 화면 요청이면 에러 페이지를 반환하고 API 요청이면 JSON 응답을 반환합니다.
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

	private static final String ERROR_VIEW = "error/error";
	private static final String NOT_FOUND_VIEW = "error/404";

	/**
	 * 서비스에서 의도적으로 발생시킨 비즈니스 예외를 처리합니다.
	 *
	 * 예를 들어 사용자 조회 실패, 권한 부족, 잘못된 마스킹 요청처럼
	 * 개발자가 예상 가능한 예외는 CustomException으로 감싸서 던집니다.
	 */
	@ExceptionHandler(CustomException.class)
	public Object handleCustomException(CustomException exception, HttpServletRequest request,
		HttpServletResponse response, Model model) {
		ErrorCode errorCode = exception.getErrorCode();
		String message = exception.getMessage();
		if (errorCode.getStatus().is5xxServerError()) {
			log.error("Application exception while handling {} {}: {}", request.getMethod(),
				request.getRequestURI(), errorCode.getCode(), exception);
		}

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
		}

		String viewName = errorCode == ErrorCode.NOT_FOUND ? NOT_FOUND_VIEW : ERROR_VIEW;
		return renderErrorView(response, model, errorCode, message, request.getRequestURI(), viewName);
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
		HttpServletResponse response,
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

		return renderErrorView(response, model, errorCode, message, request.getRequestURI(), ERROR_VIEW);
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
		HttpServletResponse response,
		Model model
	) {
		ErrorCode errorCode = ErrorCode.INVALID_REQUEST;
		String message = resolveParameterMessage(exception, errorCode);

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, message, request.getRequestURI()));
		}

		return renderErrorView(response, model, errorCode, message, request.getRequestURI(), ERROR_VIEW);
	}

	/**
	 * 존재하지 않는 정적 리소스 요청(브라우저가 자동으로 요청하는 favicon.ico 등)을 처리합니다.
	 *
	 * 예상 가능한 단순 404이므로, 아래의 최종 핸들러(Exception)로 흘러가
	 * ERROR 레벨 스택트레이스로 로그를 오염시키지 않도록 앞에서 가로챕니다.
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	public Object handleNoResourceFound(NoResourceFoundException exception, HttpServletRequest request,
		HttpServletResponse response, Model model) {
		ErrorCode errorCode = ErrorCode.NOT_FOUND;

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, errorCode.getMessage(), request.getRequestURI()));
		}

		return renderErrorView(response, model, errorCode, errorCode.getMessage(),
			request.getRequestURI(), NOT_FOUND_VIEW);
	}

	/**
	 * 별도로 처리하지 못한 예외를 마지막에 잡습니다.
	 *
	 * 예상하지 못한 예외의 상세 내용은 사용자에게 그대로 노출하지 않습니다.
	 * DB 정보, 파일 경로, 내부 클래스명 등이 화면이나 JSON에 노출될 수 있기 때문입니다.
	 */
	@ExceptionHandler(Exception.class)
	public Object handleUnexpectedException(Exception exception, HttpServletRequest request,
		HttpServletResponse response, Model model) {
		log.error("Unexpected exception while handling {} {}", request.getMethod(), request.getRequestURI(), exception);

		ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

		if (isApiRequest(request)) {
			return ResponseEntity
				.status(errorCode.getStatus())
				.body(ErrorResponse.of(errorCode, errorCode.getMessage(), request.getRequestURI()));
		}

		return renderErrorView(response, model, errorCode, errorCode.getMessage(),
			request.getRequestURI(), ERROR_VIEW);
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

	/** 화면 오류도 본문만 바꾸는 200 응답이 되지 않도록 실제 HTTP 상태를 함께 적용합니다. */
	private String renderErrorView(HttpServletResponse response, Model model, ErrorCode errorCode,
		String message, String path, String viewName) {
		response.setStatus(errorCode.getStatus().value());
		addErrorAttributes(model, errorCode, message, path);
		return viewName;
	}
}
