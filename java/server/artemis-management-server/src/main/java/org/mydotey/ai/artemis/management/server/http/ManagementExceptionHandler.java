package org.mydotey.ai.artemis.management.server.http;

import lombok.extern.slf4j.Slf4j;
import org.mydotey.ai.artemis.management.domain.AccountAccessException;
import org.mydotey.ai.artemis.management.domain.EjectionAccessException;
import org.mydotey.ai.artemis.management.domain.RegistryQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 错误语义：参数校验 400；registry 不可用 503；其余下游失败 502；未预期异常 500（堆栈只进日志）。
 */
@Slf4j
@RestControllerAdvice
public final class ManagementExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidArgument(IllegalArgumentException e) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    /** Spring MVC 客户端错误：保留 4xx 语义，避免被兜底 handler 吞成 500。 */
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> malformedRequest(Exception e) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(RegistryQueryException.class)
    public ResponseEntity<ErrorResponse> registryQuery(RegistryQueryException e) {
        HttpStatus status = RegistryQueryException.REGISTRY_UNAVAILABLE.equals(e.code())
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        log.warn("registry query failed: code={}, message={}", e.code(), e.getMessage());
        return response(status, e.code(), e.getMessage());
    }

    /** 账户存储不可用：fail-closed 语义向 API 面延伸（ADR-021）。 */
    @ExceptionHandler(AccountAccessException.class)
    public ResponseEntity<ErrorResponse> accountAccess(AccountAccessException e) {
        log.warn("account access failed: code={}, message={}", e.code(), e.getMessage());
        return response(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.getMessage());
    }

    /** 摘除/审计存储不可用（DB 态）：503，操作未落库可重试。 */
    @ExceptionHandler(EjectionAccessException.class)
    public ResponseEntity<ErrorResponse> ejectionAccess(EjectionAccessException e) {
        log.warn("ejection store access failed: code={}, message={}", e.code(), e.getMessage());
        return response(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.getMessage());
    }

    /** 内存态改密（ADR-021 双轨）：凭证事实源是配置。 */
    @ExceptionHandler(MemoryModePasswordChangeException.class)
    public ResponseEntity<ErrorResponse> memoryModePasswordChange(MemoryModePasswordChangeException e) {
        return response(HttpStatus.CONFLICT, "NOT_SUPPORTED_IN_MEMORY_MODE", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("unexpected management API failure", e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "internal error");
    }

    private static ResponseEntity<ErrorResponse> response(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(error, message));
    }

    public record ErrorResponse(String error, String message) {}
}
