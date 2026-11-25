package com.example.msmerchantbillpayments.payment;

import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.exception.PaymentException;
import com.example.msmerchantbillpayments.payment.model.Payment;
import com.example.msmerchantbillpayments.payment.model.PaymentStatus;
import com.example.msmerchantbillpayments.payment.repository.PaymentRepository;
import com.example.msmerchantbillpayments.payment.service.impl.PaymentServiceImpl;
import com.example.msmerchantbillpayments.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentServiceUnitTest {
    private AccountRepository accounts;
    private PaymentRepository payments;
    private CurrentUser access;
    private PaymentServiceImpl service;
    private final Account customer = new Account("C001", "Customer", AccountType.CUSTOMER, new BigDecimal("1000.00"));
    private final Account merchant = new Account("M001", "Merchant", AccountType.MERCHANT, BigDecimal.ZERO);
    private final BillPaymentRequest request = new BillPaymentRequest("C001", "M001", new BigDecimal("100"));

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        payments = mock(PaymentRepository.class);
        access = mock(CurrentUser.class);
        service = new PaymentServiceImpl(accounts, payments, access);
        when(access.currentUsername()).thenReturn("Wycliffe");
        when(accounts.findByNumberForUpdate("C001")).thenReturn(Optional.of(customer));
        when(accounts.findByNumberForUpdate("M001")).thenReturn(Optional.of(merchant));
        when(payments.creditBankFee(new BigDecimal("30.00"))).thenReturn(true);
    }

    @Test
    void calculatesDebitsCreditsAndSuccessfulResponse() {
        var result = service.pay(request, "unit-key");
        verify(accounts).updateBalance("C001", new BigDecimal("870.00"));
        verify(accounts).updateBalance("M001", new BigDecimal("100.00"));
        verify(payments).creditBankFee(new BigDecimal("30.00"));
        var captured = ArgumentCaptor.forClass(Payment.class);
        verify(payments).insert(captured.capture());
        assertEquals(result.reference(), captured.getValue().reference());
        assertEquals("unit-key", captured.getValue().idempotencyKey());
        assertEquals("Wycliffe", captured.getValue().requester());
        assertEquals(PaymentStatus.SUCCESS, result.status());
        assertFalse(result.replayed());
    }

    @Test
    void insufficientFundsDoNotWriteBalancesOrPayment() {
        var error = assertThrows(PaymentException.class, () -> service.pay(
                new BillPaymentRequest("C001", "M001", new BigDecimal("1000")), "unit-key"));
        assertEquals("INSUFFICIENT_FUNDS", error.getCode());
        verify(accounts, never()).updateBalance(anyString(), any());
        verify(payments, never()).creditBankFee(any());
        verify(payments, never()).insert(any());
    }

    @Test
    void replayDoesNotWriteOrLockAccountsAgain() {
        Payment original = original();
        when(payments.findByRequest("Wycliffe", "unit-key")).thenReturn(Optional.of(original));
        var result = service.pay(request, "unit-key");
        assertTrue(result.replayed());
        assertEquals(original.reference(), result.reference());
        verify(accounts, never()).findByNumberForUpdate(anyString());
        verify(accounts, never()).updateBalance(anyString(), any());
        verify(payments, never()).insert(any());
    }

    @Test
    void mismatchedReplayIsRejected() {
        when(payments.findByRequest("Wycliffe", "unit-key")).thenReturn(Optional.of(original()));
        var error = assertThrows(PaymentException.class, () -> service.pay(
                new BillPaymentRequest("C001", "M001", new BigDecimal("101")), "unit-key"));
        assertEquals("IDEMPOTENCY_CONFLICT", error.getCode());
        verify(accounts, never()).updateBalance(anyString(), any());
    }

    @Test
    void missingAuthenticationStopsPayment() {
        when(access.currentUsername()).thenThrow(new AuthenticationCredentialsNotFoundException("Authentication required"));
        assertThrows(AuthenticationCredentialsNotFoundException.class, () -> service.pay(request, "unit-key"));
        verifyNoInteractions(payments);
        verify(accounts, never()).updateBalance(anyString(), any());
    }

    @Test
    void bankFeeFailurePropagatesForTransactionRollback() {
        when(payments.creditBankFee(any())).thenReturn(false);
        var error = assertThrows(PaymentException.class, () -> service.pay(request, "unit-key"));
        assertEquals("BANK_FEE_ACCOUNT_UNAVAILABLE", error.getCode());
        verify(payments, never()).insert(any());
    }

    @Test
    void paymentInsertFailureIsNotSwallowed() {
        doThrow(new DataIntegrityViolationException("Insert failed")).when(payments).insert(any());
        assertThrows(DataIntegrityViolationException.class, () -> service.pay(request, "unit-key"));
    }

    private Payment original() {
        return new Payment(UUID.randomUUID(), "C001", "M001", new BigDecimal("100.00"), new BigDecimal("30.00"),
                new BigDecimal("870.00"), Instant.parse("2026-01-01T00:00:00Z"), "Wycliffe", "unit-key", PaymentStatus.SUCCESS);
    }
}
