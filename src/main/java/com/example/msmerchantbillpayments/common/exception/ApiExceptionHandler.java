package com.example.msmerchantbillpayments.common.exception;

import com.example.msmerchantbillpayments.account.exception.DuplicateAccountException;
import com.example.msmerchantbillpayments.payment.exception.PaymentException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", errors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                "Invalid JSON or field value.", Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(new ApiError(Instant.now(), status.value(), "HTTP_ERROR",
                "The request could not be processed.", Map.of()), headers, status);
    }

    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<Object> handleDuplicate(DuplicateAccountException ex) {
        log.warn("Account registration rejected: duplicate account number");
        return error(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_EXISTS", ex.getMessage(), Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", Map.of());
    }

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<Object> handlePayment(PaymentException ex) {
        HttpStatus status = switch (ex.getCode()) {
            case "ACCOUNT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "INVALID_PAYMENT_ACCOUNTS", "INVALID_ACCOUNT_TYPE" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.CONFLICT;
        };
        log.warn("Bill payment rejected: code={}", ex.getCode());
        return error(status, ex.getCode(), ex.getMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unexpected request failure", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later.", Map.of());
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message,
                                         Map<String, String> errors) {
        return ResponseEntity.status(status)
                .body(new ApiError(Instant.now(), status.value(), code, message, errors));
    }
}
