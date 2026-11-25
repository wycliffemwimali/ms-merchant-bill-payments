package com.example.msmerchantbillpayments.payment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record Payment(UUID reference, String customerAccountNumber, String merchantAccountNumber,
                      BigDecimal amount, BigDecimal fee, BigDecimal customerBalanceAfter, Instant paidAt) {
}
