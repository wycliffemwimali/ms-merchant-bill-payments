package com.example.msmerchantbillpayments.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record BillPaymentRequest(
        @NotBlank @Size(max = 32) String customerAccountNumber,
        @NotBlank @Size(max = 32) String merchantAccountNumber,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2)
        BigDecimal amount
) {
}
