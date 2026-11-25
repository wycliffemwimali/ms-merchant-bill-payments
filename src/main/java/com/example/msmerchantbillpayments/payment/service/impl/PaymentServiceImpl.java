package com.example.msmerchantbillpayments.payment.service.impl;

import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentResponse;
import com.example.msmerchantbillpayments.payment.exception.PaymentException;
import com.example.msmerchantbillpayments.payment.model.Payment;
import com.example.msmerchantbillpayments.payment.model.PaymentStatus;
import com.example.msmerchantbillpayments.security.CurrentUser;
import com.example.msmerchantbillpayments.payment.repository.PaymentRepository;
import com.example.msmerchantbillpayments.payment.service.PaymentService;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

@Service
@Validated
public class PaymentServiceImpl implements PaymentService {
    private static final BigDecimal FEE = new BigDecimal("30.00");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");
    private final AccountRepository accountRepository;
    private final PaymentRepository paymentRepository;
    private final CurrentUser currentUser;

    public PaymentServiceImpl(AccountRepository accountRepository, PaymentRepository paymentRepository, CurrentUser currentUser) {
        this.accountRepository = accountRepository;
        this.paymentRepository = paymentRepository;
        this.currentUser = currentUser;
    }

    @Override
    @PreAuthorize("hasAnyRole('ADMIN', 'CUSTOMER')")
    @Transactional(timeout = 10)
    public BillPaymentResponse pay(BillPaymentRequest request, String idempotencyKey) {
        String requester = currentUser.currentUsername();
        paymentRepository.lockRequest(requester, idempotencyKey);
        var previous = paymentRepository.findByRequest(requester, idempotencyKey);
        if (previous.isPresent()) {
            Payment payment = previous.get();
            if (!payment.customerAccountNumber().equals(request.customerAccountNumber())
                    || !payment.merchantAccountNumber().equals(request.merchantAccountNumber())
                    || payment.amount().compareTo(request.amount()) != 0) {
                throw new PaymentException("IDEMPOTENCY_CONFLICT", "This idempotency key was already used for a different payment.");
            }
            return response(payment, true);
        }
        if (request.customerAccountNumber().equals(request.merchantAccountNumber())) {
            throw new PaymentException("INVALID_PAYMENT_ACCOUNTS", "Customer and merchant accounts must be different.");
        }

        var accounts = new HashMap<String, Account>();
        for (String number : List.of(request.customerAccountNumber(), request.merchantAccountNumber())
                .stream().sorted().toList()) {
            Account account = accountRepository.findByNumberForUpdate(number)
                    .orElseThrow(() -> new PaymentException("ACCOUNT_NOT_FOUND", "A payment account was not found."));
            accounts.put(number, account);
        }

        Account customer = accounts.get(request.customerAccountNumber());
        Account merchant = accounts.get(request.merchantAccountNumber());
        if (customer.accountType() != AccountType.CUSTOMER || merchant.accountType() != AccountType.MERCHANT) {
            throw new PaymentException("INVALID_ACCOUNT_TYPE", "Payment requires a CUSTOMER payer and a MERCHANT recipient.");
        }

        BigDecimal amount = request.amount().setScale(2);
        BigDecimal totalDebit = amount.add(FEE);
        if (customer.balance().compareTo(totalDebit) < 0) {
            throw new PaymentException("INSUFFICIENT_FUNDS", "Available balance must cover the bill amount and KES 30 fee.");
        }

        BigDecimal merchantBalance = merchant.balance().add(amount);
        if (merchantBalance.compareTo(MAX_BALANCE) > 0) {
            throw new PaymentException("BALANCE_LIMIT_EXCEEDED", "The merchant balance would exceed the supported limit.");
        }

        BigDecimal customerBalance = customer.balance().subtract(totalDebit);
        accountRepository.updateBalance(customer.accountNumber(), customerBalance);
        accountRepository.updateBalance(merchant.accountNumber(), merchantBalance);
        if (!paymentRepository.creditBankFee(FEE)) {
            throw new PaymentException("BANK_FEE_ACCOUNT_UNAVAILABLE", "The bank fee account cannot receive the charge.");
        }

        var payment = new Payment(UUID.randomUUID(), customer.accountNumber(), merchant.accountNumber(),
                amount, FEE, customerBalance, Instant.now().truncatedTo(ChronoUnit.MICROS),
                requester, idempotencyKey, PaymentStatus.SUCCESS);
        paymentRepository.insert(payment);
        return response(payment, false);
    }

    private BillPaymentResponse response(Payment payment, boolean replayed) {
        return new BillPaymentResponse(payment.reference(), payment.customerAccountNumber(), payment.merchantAccountNumber(),
                payment.amount(), payment.fee(), payment.amount().add(payment.fee()), payment.customerBalanceAfter(),
                "KES", payment.paidAt(), payment.status(), replayed);
    }
}
