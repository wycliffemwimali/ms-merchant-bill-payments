package com.example.msmerchantbillpayments.statement.exception;

public class StatementException extends RuntimeException {
    private final String code;

    public StatementException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
