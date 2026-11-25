package com.example.msmerchantbillpayments.statement;

import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.service.PaymentService;
import com.example.msmerchantbillpayments.statement.service.StatementService;
import com.example.msmerchantbillpayments.support.PostgresTestConfiguration;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@WithMockUser(username = "admin", roles = "ADMIN")
@Import(PostgresTestConfiguration.class)
class MiniStatementTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService paymentService;
    @Autowired private StatementService statementService;
    @MockitoSpyBean private AccountRepository accountRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())).build();
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("UPDATE bank_fee_account SET balance = 0 WHERE id = 1");
        jdbc.update("""
                INSERT INTO accounts (account_number, name, account_type, balance) VALUES
                ('C001', 'Wycliffe Mwimali', 'CUSTOMER', 5000),
                ('C002', 'Second Customer', 'CUSTOMER', 5000),
                ('M001', 'Demo Merchant', 'MERCHANT', 0)
                """);
    }

    @Test
    void returnsBalanceAndEmptyHistoryForNewCustomer() throws Exception {
        mvc.perform(get("/api/accounts/C001/mini-statement"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountNumber").value("C001"))
                .andExpect(jsonPath("$.name").value("Wycliffe Mwimali"))
                .andExpect(jsonPath("$.availableBalance").value(5000))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.transactions").isEmpty());
    }

    @Test
    void returnsAllPaymentDetailsAndDoesNotChangeBalances() throws Exception {
        var payment = pay("C001", "200.25");
        mvc.perform(get("/api/accounts/C001/mini-statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance").value(4769.75))
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].reference").value(payment.reference().toString()))
                .andExpect(jsonPath("$.transactions[0].merchantAccountNumber").value("M001"))
                .andExpect(jsonPath("$.transactions[0].amount").value(200.25))
                .andExpect(jsonPath("$.transactions[0].fee").value(30))
                .andExpect(jsonPath("$.transactions[0].totalDebit").value(230.25))
                .andExpect(jsonPath("$.transactions[0].balanceAfter").value(4769.75))
                .andExpect(jsonPath("$.transactions[0].paidAt").value(payment.paidAt().toString()));
        assertEquals(new BigDecimal("4769.75"), balance("C001"));
        assertEquals(new BigDecimal("200.25"), balance("M001"));
        assertEquals(new BigDecimal("30.00"), jdbc.queryForObject("SELECT balance FROM bank_fee_account", BigDecimal.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
    }

    @Test
    void returnsOnlyLatestFiveInDescendingTimeOrder() {
        var references = new ArrayList<UUID>();
        for (int i = 0; i < 7; i++) {
            var payment = pay("C001", "100");
            references.add(payment.reference());
            jdbc.update("UPDATE payments SET paid_at = ? WHERE reference = ?",
                    Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)), payment.reference());
        }
        var result = statementService.getMiniStatement("C001");
        assertEquals(new BigDecimal("4090.00"), result.availableBalance());
        assertEquals(List.of(references.get(6), references.get(5), references.get(4), references.get(3), references.get(2)),
                result.transactions().stream().map(transaction -> transaction.reference()).toList());
        assertEquals(new BigDecimal("4090.00"), result.transactions().get(0).balanceAfter());
    }

    @Test
    void usesStableReferenceOrderForEqualTimestamps() {
        var first = pay("C001", "100");
        var second = pay("C001", "100");
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        jdbc.update("UPDATE payments SET reference = ?, paid_at = ? WHERE reference = ?", lower,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), first.reference());
        jdbc.update("UPDATE payments SET reference = ?, paid_at = ? WHERE reference = ?", higher,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), second.reference());
        assertEquals(List.of(higher, lower), statementService.getMiniStatement("C001").transactions().stream()
                .map(transaction -> transaction.reference()).toList());
    }

    @Test
    void excludesOtherCustomersPayments() {
        var ownPayment = pay("C001", "100");
        for (int i = 0; i < 6; i++) pay("C002", "100");
        var statement = statementService.getMiniStatement("C001");
        assertEquals(1, statement.transactions().size());
        assertEquals(ownPayment.reference(), statement.transactions().get(0).reference());
        assertEquals(new BigDecimal("4870.00"), statement.availableBalance());
    }

    @Test
    void rejectsUnknownAndMerchantAccounts() throws Exception {
        mvc.perform(get("/api/accounts/UNKNOWN/mini-statement"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/accounts/M001/mini-statement"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_TYPE"));
    }

    @Test
    void validatesAccountNumberThroughInterfaceAndHttp() throws Exception {
        assertThrows(ConstraintViolationException.class, () -> statementService.getMiniStatement(null));
        assertThrows(ConstraintViolationException.class, () -> statementService.getMiniStatement(" "));
        mvc.perform(get("/api/accounts/{accountNumber}/mini-statement", "A".repeat(33)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void balanceAndHistoryUseTheSameSnapshotDuringConcurrentPayment() throws Exception {
        var executor = new DelegatingSecurityContextExecutorService(Executors.newSingleThreadExecutor());
        var paymentTriggered = new AtomicBoolean();
        doAnswer(invocation -> {
            Object account = invocation.callRealMethod();
            if (paymentTriggered.compareAndSet(false, true)) {
                executor.submit(() -> pay("C001", "100")).get(5, TimeUnit.SECONDS);
            }
            return account;
        }).when(accountRepository).findByNumber("C001");
        try {
            var before = statementService.getMiniStatement("C001");
            assertEquals(new BigDecimal("5000.00"), before.availableBalance());
            assertTrue(before.transactions().isEmpty());
            var after = statementService.getMiniStatement("C001");
            assertEquals(new BigDecimal("4870.00"), after.availableBalance());
            assertEquals(1, after.transactions().size());
            assertEquals(after.availableBalance(), after.transactions().get(0).balanceAfter());
        } finally {
            executor.shutdownNow();
        }
    }

    private com.example.msmerchantbillpayments.payment.dto.BillPaymentResponse pay(String customer, String amount) {
        return paymentService.pay(new BillPaymentRequest(customer, "M001", new BigDecimal(amount)), java.util.UUID.randomUUID().toString());
    }

    private BigDecimal balance(String number) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = ?", BigDecimal.class, number);
    }
}
