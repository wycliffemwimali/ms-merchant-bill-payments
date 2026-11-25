package com.example.msmerchantbillpayments.statement.service.impl;

import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import com.example.msmerchantbillpayments.payment.repository.PaymentRepository;
import com.example.msmerchantbillpayments.statement.dto.MiniStatementResponse;
import com.example.msmerchantbillpayments.statement.dto.StatementTransaction;
import com.example.msmerchantbillpayments.statement.exception.StatementException;
import com.example.msmerchantbillpayments.statement.service.StatementService;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class StatementServiceImpl implements StatementService {
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;

    public StatementServiceImpl(AccountRepository accountRepository, PaymentRepository paymentRepository) {
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 10)
    public MiniStatementResponse getMiniStatement(String accountNumber) {
        Account account = accountRepository.findByNumber(accountNumber)
                .orElseThrow(() -> new StatementException("ACCOUNT_NOT_FOUND", "The account was not found."));
        if (account.accountType() != AccountType.CUSTOMER) {
            throw new StatementException("INVALID_ACCOUNT_TYPE", "Mini-statements are available for CUSTOMER accounts only.");
        }

        var transactions = paymentRepository.findLatestFiveByCustomer(accountNumber).stream()
                .map(payment -> new StatementTransaction(payment.reference(), payment.merchantAccountNumber(),
                        payment.amount(), payment.fee(), payment.amount().add(payment.fee()),
                        payment.customerBalanceAfter(), payment.paidAt()))
                .toList();
        return new MiniStatementResponse(account.accountNumber(), account.name(), account.balance(), "KES", transactions);
    }
}
