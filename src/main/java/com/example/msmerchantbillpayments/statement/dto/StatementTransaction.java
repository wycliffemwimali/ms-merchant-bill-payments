package com.example.msmerchantbillpayments.statement.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record StatementTransaction(UUID reference, String merchantAccountNumber, BigDecimal amount,
                                   BigDecimal fee, BigDecimal totalDebit, BigDecimal balanceAfter, Instant paidAt) {
}
