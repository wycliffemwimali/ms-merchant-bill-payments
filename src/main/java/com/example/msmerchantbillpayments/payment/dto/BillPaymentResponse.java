package com.example.msmerchantbillpayments.payment.dto;

import com.example.msmerchantbillpayments.payment.model.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record BillPaymentResponse(UUID reference, String customerAccountNumber, String merchantAccountNumber,
                                  BigDecimal amount, BigDecimal fee, BigDecimal totalDebit,
                                  BigDecimal availableBalance, String currency, Instant paidAt, PaymentStatus status, boolean replayed) {
}
