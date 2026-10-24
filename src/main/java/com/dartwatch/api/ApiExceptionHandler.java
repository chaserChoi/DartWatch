package com.dartwatch.api;

import com.dartwatch.opendart.OpenDartException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) { return problem(ex.getStatus(), ex.getCode(), ex.getMessage()); }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, MissingServletRequestParameterException.class})
    ProblemDetail invalidInput(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "요청 값이 올바르지 않습니다.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "이미 등록되었거나 참조 관계와 충돌하는 요청입니다.");
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ProblemDetail locked(PessimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "RESOURCE_BUSY", "다른 작업이 진행 중입니다. 잠시 후 다시 시도하세요.");
    }

    @ExceptionHandler(OpenDartException.class)
    ProblemDetail upstream(OpenDartException ex) {
        HttpStatus status = switch (ex.getKind()) {
            case CONFIGURATION -> HttpStatus.SERVICE_UNAVAILABLE;
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case RATE_LIMIT, UPSTREAM_SYSTEM -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        var detail = problem(status, "OPENDART_" + ex.getKind().name(), ex.getMessage());
        if (ex.getStatus() != null) detail.setProperty("upstreamStatus", ex.getStatus());
        return detail;
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail persistence(Exception ex) {
        log.warn("API request failed: kind=PERSISTENCE");
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE", "데이터베이스를 사용할 수 없습니다. 잠시 후 다시 시도하세요.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail notFound(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "요청한 경로를 찾을 수 없습니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail unsupportedMedia(HttpMediaTypeNotSupportedException ex) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 요청 형식입니다.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ProblemDetail> unacceptableMedia(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE", "지원하지 않는 응답 형식입니다."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMethod(HttpRequestMethodNotSupportedException ex) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) response.allow(ex.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        return response.body(problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 요청 메서드입니다."));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("API request failed: kind=INTERNAL");
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
}
