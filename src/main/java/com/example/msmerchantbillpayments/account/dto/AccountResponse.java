package com.example.msmerchantbillpayments.account.dto;

import com.example.msmerchantbillpayments.account.model.AccountType;
import java.math.BigDecimal;

public record AccountResponse(String accountNumber, String name, AccountType accountType,
                              BigDecimal balance, String currency) {
}
