package com.example.msmerchantbillpayments.account.exception;

public class DuplicateAccountException extends RuntimeException {
    public DuplicateAccountException() {
        super("An account with this account number already exists.");
    }
}
