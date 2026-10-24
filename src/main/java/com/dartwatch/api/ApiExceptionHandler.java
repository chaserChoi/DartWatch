package com.dartwatch.api;

import com.dartwatch.opendart.OpenDartException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException ex) { return problem(ex.getStatus(), ex.getCode(), ex.getMessage()); }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
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

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
}
