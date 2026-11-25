package com.example.msmerchantbillpayments.account.service.impl;

import com.example.msmerchantbillpayments.account.dto.AccountResponse;
import com.example.msmerchantbillpayments.account.dto.RegisterAccountRequest;
import com.example.msmerchantbillpayments.account.exception.DuplicateAccountException;
import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import com.example.msmerchantbillpayments.account.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class AccountServiceImpl implements AccountService {
    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);
    private final AccountRepository repository;

    public AccountServiceImpl(AccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public AccountResponse register(RegisterAccountRequest request) {
        var account = new Account(request.accountNumber(), request.name().strip(),
                request.accountType(), request.openingBalance().setScale(2));
        try {
            repository.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new DuplicateAccountException();
        }
        log.info("Registered {} account", account.accountType());
        return new AccountResponse(account.accountNumber(), account.name(), account.accountType(),
                account.balance(), "KES");
    }
}
