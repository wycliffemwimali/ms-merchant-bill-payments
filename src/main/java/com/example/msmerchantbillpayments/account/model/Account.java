package com.example.msmerchantbillpayments.account.model;

import java.math.BigDecimal;

public record Account(String accountNumber, String name, AccountType accountType, BigDecimal balance) {
}
