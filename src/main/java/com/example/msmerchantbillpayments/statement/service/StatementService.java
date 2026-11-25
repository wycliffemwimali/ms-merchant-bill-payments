package com.example.msmerchantbillpayments.statement.service;

import com.example.msmerchantbillpayments.statement.dto.MiniStatementResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public interface StatementService {
    MiniStatementResponse getMiniStatement(@NotBlank @Size(max = 32) String accountNumber);
}
