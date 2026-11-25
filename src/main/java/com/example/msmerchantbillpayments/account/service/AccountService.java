package com.example.msmerchantbillpayments.account.service;

import com.example.msmerchantbillpayments.account.dto.AccountResponse;
import com.example.msmerchantbillpayments.account.dto.RegisterAccountRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public interface AccountService {
    AccountResponse register(@NotNull @Valid RegisterAccountRequest request);
}
