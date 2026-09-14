package com.flashsale.purchase.web;

import com.flashsale.purchase.exception.ConflictException;
import com.flashsale.purchase.exception.NotFoundException;
import com.flashsale.purchase.exception.ServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ProblemDetail handleServiceUnavailable(ServiceUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getAllErrors().stream()
            .map(org.springframework.validation.ObjectError::getDefaultMessage)
            .reduce((a, b) -> a + "；" + b)
            .orElse("輸入資料驗證失敗");
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", detail, request);
    }

    private ProblemDetail build(HttpStatus status, String code, String detail, HttpServletRequest request) {
        return ProblemDetails.of(status, code, detail, request.getRequestURI());
    }
}
