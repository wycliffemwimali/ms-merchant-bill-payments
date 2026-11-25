package com.example.msmerchantbillpayments.payment.service;

import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public interface PaymentService {
    BillPaymentResponse pay(@NotNull @Valid BillPaymentRequest request, @NotBlank @Size(max = 128) String idempotencyKey);
}
