package vip.mate.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import vip.mate.agent.platform.PlatformServiceException;
import vip.mate.common.result.R;
import vip.mate.i18n.I18nService;
import vip.mate.skill.lifecycle.ConfirmRequiredException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global exception handler.
 * <p>
 * MateClawException messages are translated through I18nService when the
 * exception provides a message key. The JSON body keeps the project-wide
 * R envelope while the HTTP status mirrors the failure class.
 *
 * @author MateClaw Team
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final I18nService i18nService;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<R<Void>> handleAsyncTimeout(AsyncRequestTimeoutException e,
                                                      HttpServletRequest request,
                                                      HttpServletResponse response) {
        if (isSseRequest(request) || response.isCommitted()) {
            log.debug("SSE async timeout (normal lifecycle): {} {}", request.getMethod(), request.getRequestURI());
            // Return no body for SSE so the framework can end the async request.
            return null;
        }
        log.warn("Async request timeout: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(R.fail(503, "Request timeout, please try again"));
    }

    @ExceptionHandler(MateClawException.class)
    public ResponseEntity<R<Void>> handleMateClawException(MateClawException e) {
        log.warn("Business exception: [{}] {}", e.getCode(), e.getMessage());
        String msg = translateExceptionMsg(e);
        return ResponseEntity.status(httpStatusForCode(e.getCode())).body(R.fail(e.getCode(), msg));
    }

    /**
     * Translate exception message via i18n.
     * If the exception has a msgKey, use it to look up the translated message.
     * Otherwise return the original message as-is.
     */
    private String translateExceptionMsg(MateClawException e) {
        String msgKey = e.getMsgKey();
        if (msgKey != null && !msgKey.isEmpty()) {
            String translated = i18nService.msg(msgKey);
            if (!translated.equals(msgKey)) {
                return translated;
            }
        }
        return e.getMessage();
    }

    /**
     * A mutation that needs a second, explicit confirmation. Returns a real
     * HTTP 409 with a structured body so the client can branch on the status
     * code and render a confirm dialog from {@code boundAgents}.
     */
    @ExceptionHandler(ConfirmRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleConfirmRequired(ConfirmRequiredException e) {
        log.info("Confirm required: [{}] {}", e.getCode(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getCode());
        body.put("message", e.getMessage());
        body.put("boundAgents", e.getBoundAgents());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Platform service call failures (network errors, auth errors, business errors).
     * <p>
     * Returns HTTP 503 for unreachable platform, 401 for auth errors, and 502 for other platform errors.
     */
    @ExceptionHandler(PlatformServiceException.class)
    public ResponseEntity<R<Void>> handlePlatformServiceException(PlatformServiceException e) {
        log.warn("Platform service error [{}]: {}", e.getErrorType(), e.getMessage());

        int httpCode;
        String message;

        switch (e.getErrorType()) {
            case UNREACHABLE:
                httpCode = 503;
                message = "平台服务不可用，请稍后重试";
                break;
            case AUTH_ERROR:
                httpCode = 401;
                message = "平台认证失败，请重新登录";
                break;
            case BUSINESS_ERROR:
                httpCode = 502;
                message = "平台业务错误: " + e.getMessage();
                break;
            case PARSE_ERROR:
                httpCode = 502;
                message = "平台响应解析失败";
                break;
            default:
                httpCode = 500;
                message = "平台服务调用失败";
        }

        return ResponseEntity.status(httpCode).body(R.fail(httpCode, message));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        log.warn("Validation failed: {}", msg);
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    /**
     * A path variable or request parameter could not be coerced into the
     * handler's declared type (e.g. a non-numeric segment on an {@code /{id}}
     * route bound to {@code Long}). This is a malformed client request, not a
     * server fault, so it must surface as 400 — never a 500 with a full stack
     * trace from the catch-all handler below.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e,
                                                      HttpServletRequest request) {
        String required = e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "expected type";
        String msg = "Invalid value for parameter '" + e.getName() + "': expected " + required;
        log.warn("Argument type mismatch: {} {} - {}", request.getMethod(), request.getRequestURI(), msg);
        return ResponseEntity.badRequest().body(R.fail(400, msg));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResourceFound(NoResourceFoundException e,
                                                         HttpServletRequest request) {
        log.warn("Resource not found: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(404, "Resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleException(Exception e,
                                                   HttpServletRequest request,
                                                   HttpServletResponse response) {
        if (response.isCommitted() || isSseRequest(request)) {
            log.warn("Exception after response committed or during SSE (suppressed): {} {} - {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
            return null;
        }
        log.error("Unexpected error: {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(R.fail("Internal server error"));
    }

    /**
     * Identify SSE requests from the Accept header, Content-Type, or known stream path.
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        // Fallback path match for stream endpoints that omit explicit headers.
        String uri = request.getRequestURI();
        return uri != null && uri.contains("/chat/stream");
    }

    private HttpStatus httpStatusForCode(int code) {
        HttpStatus status = HttpStatus.resolve(code);
        return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
