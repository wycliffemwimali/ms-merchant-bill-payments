package com.example.msmerchantbillpayments.account.dto;

import com.example.msmerchantbillpayments.account.model.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RegisterAccountRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9-]{2,31}",
                message = "must be 3–32 letters, digits, or hyphens, starting with a letter or digit")
        String accountNumber,
        @NotBlank @Size(max = 120) String name,
        @NotNull AccountType accountType,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2)
        BigDecimal openingBalance
) {
}
