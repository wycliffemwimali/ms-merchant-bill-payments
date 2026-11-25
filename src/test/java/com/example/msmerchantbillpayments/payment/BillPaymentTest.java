package com.example.msmerchantbillpayments.payment;

import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.exception.PaymentException;
import com.example.msmerchantbillpayments.payment.service.PaymentService;
import com.example.msmerchantbillpayments.support.PostgresTestConfiguration;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class BillPaymentTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("UPDATE bank_fee_account SET balance = 0 WHERE id = 1");
        account("C001", "CUSTOMER", "1000.00");
        account("M001", "MERCHANT", "100.00");
    }

    @Test
    void paysBillAndPersistsAllBalancesAndReference() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("C001", "M001", "200.25")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").isString())
                .andExpect(jsonPath("$.customerAccountNumber").value("C001"))
                .andExpect(jsonPath("$.merchantAccountNumber").value("M001"))
                .andExpect(jsonPath("$.amount").value(200.25))
                .andExpect(jsonPath("$.fee").value(30.00))
                .andExpect(jsonPath("$.totalDebit").value(230.25))
                .andExpect(jsonPath("$.availableBalance").value(769.75))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.paidAt").exists());
        assertBalances("769.75", "300.25", "30.00");
        assertEquals(1, paymentCount());
        assertNotNull(jdbc.queryForObject("SELECT reference FROM payments", UUID.class));
        assertEquals(new BigDecimal("769.75"), jdbc.queryForObject(
                "SELECT customer_balance_after FROM payments", BigDecimal.class));
    }

    @Test
    void returnsPersistedUniqueReferences() {
        var first = service.pay(request("C001", "100"));
        var second = service.pay(request("C001", "100"));
        assertNotEquals(first.reference(), second.reference());
        assertEquals(2, paymentCount());
        assertEquals(first.paidAt(), jdbc.queryForObject("SELECT paid_at FROM payments WHERE reference = ?",
                (row, index) -> row.getTimestamp(1).toInstant(), first.reference()));
        assertEquals(second.reference(), jdbc.queryForObject("SELECT reference FROM payments WHERE reference = ?",
                UUID.class, second.reference()));
        assertBalances("740.00", "300.00", "60.00");
    }

    @Test
    void acceptsExactFundsIncludingFee() {
        service.pay(request("C001", "970"));
        assertBalances("0.00", "1070.00", "30.00");
    }

    @Test
    void requiresFundsForTheFeeToo() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("C001", "M001", "1000")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.001", "100000000000000000", "null"})
    void rejectsInvalidAmounts(String amount) throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("C001", "M001", amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
        assertUnchanged();
    }

    @Test
    void rejectsMissingFieldsAndMalformedJson() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.customerAccountNumber").exists())
                .andExpect(jsonPath("$.errors.merchantAccountNumber").exists())
                .andExpect(jsonPath("$.errors.amount").exists());
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));
        assertUnchanged();
    }

    @Test
    void rejectsMissingCustomerOrMerchant() throws Exception {
        for (String body : List.of(payload("UNKNOWN", "M001", "100"), payload("C001", "UNKNOWN", "100"))) {
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }
        assertUnchanged();
    }

    @Test
    void rejectsSameAccountAndIncorrectAccountRoles() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("C001", "C001", "100")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAYMENT_ACCOUNTS"));
        for (String body : List.of(payload("M001", "C001", "100"),
                payload("C001", "C002", "100"), payload("M001", "M002", "100"))) {
            accountIfAbsent("C002", "CUSTOMER");
            accountIfAbsent("M002", "MERCHANT");
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_TYPE"));
        }
        assertUnchanged();
    }

    @Test
    void rejectsMerchantBalanceOverflow() {
        jdbc.update("UPDATE accounts SET balance = 99999999999999999.99 WHERE account_number = 'M001'");
        PaymentException error = assertThrows(PaymentException.class, () -> service.pay(request("C001", "1")));
        assertEquals("BALANCE_LIMIT_EXCEEDED", error.getCode());
        assertBalances("1000.00", "99999999999999999.99", "0.00");
        assertEquals(0, paymentCount());
    }

    @Test
    void rollsBackAccountUpdatesWhenFeeCreditFails() {
        jdbc.update("UPDATE bank_fee_account SET balance = 99999999999999999.99 WHERE id = 1");
        PaymentException error = assertThrows(PaymentException.class, () -> service.pay(request("C001", "100")));
        assertEquals("BANK_FEE_ACCOUNT_UNAVAILABLE", error.getCode());
        assertBalances("1000.00", "100.00", "99999999999999999.99");
        assertEquals(0, paymentCount());
    }

    @Test
    void rollsBackAllThreeBalancesWhenPaymentRecordFails() throws Exception {
        jdbc.execute("ALTER TABLE payments ADD CONSTRAINT test_reject_payment CHECK (amount <> 777.00)");
        try {
            mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                            .content(payload("C001", "M001", "777")))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                    .andExpect(jsonPath("$.message").value("An unexpected error occurred. Please try again later."));
            assertUnchanged();
        } finally {
            jdbc.execute("ALTER TABLE payments DROP CONSTRAINT test_reject_payment");
        }
    }

    @Test
    void concurrentPaymentsCannotOverspend() throws Exception {
        jdbc.update("UPDATE accounts SET balance = 200 WHERE account_number = 'C001'");
        List<String> outcomes = concurrently(List.of(request("C001", "100"), request("C001", "100")));
        assertEquals(1, outcomes.stream().filter("SUCCESS"::equals).count());
        assertEquals(1, outcomes.stream().filter("INSUFFICIENT_FUNDS"::equals).count());
        assertBalances("70.00", "200.00", "30.00");
        assertEquals(1, paymentCount());
    }

    @Test
    void concurrentCustomersDoNotLoseMerchantOrFeeCredits() throws Exception {
        account("C002", "CUSTOMER", "1000.00");
        assertEquals(List.of("SUCCESS", "SUCCESS"), concurrently(List.of(
                request("C001", "100"), request("C002", "200"))));
        assertBalances("870.00", "400.00", "60.00");
        assertEquals(new BigDecimal("770.00"), balance("C002"));
        assertEquals(2, paymentCount());
    }

    @Test
    void restartingSchemaInitializationPreservesBalancesAndPayments() {
        service.pay(request("C001", "100"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        assertBalances("870.00", "200.00", "30.00");
        assertEquals(1, paymentCount());
    }

    @Test
    void validatesRequestsThroughServiceInterface() {
        assertThrows(ConstraintViolationException.class, () -> service.pay(request("C001", "-1")));
        assertThrows(ConstraintViolationException.class, () -> service.pay(null));
        assertUnchanged();
    }

    private List<String> concurrently(List<BillPaymentRequest> requests) throws Exception {
        var executor = Executors.newFixedThreadPool(requests.size());
        var ready = new CountDownLatch(requests.size());
        var start = new CountDownLatch(1);
        try {
            var futures = requests.stream().map(request -> executor.submit((Callable<String>) () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    service.pay(request);
                    return "SUCCESS";
                } catch (PaymentException ex) {
                    return ex.getCode();
                }
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var outcomes = new java.util.ArrayList<String>();
            for (var future : futures) outcomes.add(future.get(15, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private void account(String number, String type, String balance) {
        jdbc.update("INSERT INTO accounts (account_number, name, account_type, balance) VALUES (?, ?, ?, ?)",
                number, number, type, new BigDecimal(balance));
    }

    private void accountIfAbsent(String number, String type) {
        jdbc.update("INSERT INTO accounts (account_number, name, account_type, balance) VALUES (?, ?, ?, 1000) ON CONFLICT DO NOTHING",
                number, number, type);
    }

    private BillPaymentRequest request(String customer, String amount) {
        return new BillPaymentRequest(customer, "M001", new BigDecimal(amount));
    }

    private String payload(String customer, String merchant, String amount) {
        return """
                {"customerAccountNumber":"%s","merchantAccountNumber":"%s","amount":%s}
                """.formatted(customer, merchant, amount);
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = ?", BigDecimal.class, account);
    }

    private int paymentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);
    }

    private void assertBalances(String customer, String merchant, String bank) {
        assertEquals(new BigDecimal(customer), balance("C001"));
        assertEquals(new BigDecimal(merchant), balance("M001"));
        assertEquals(new BigDecimal(bank), jdbc.queryForObject("SELECT balance FROM bank_fee_account WHERE id = 1", BigDecimal.class));
    }

    private void assertUnchanged() {
        assertBalances("1000.00", "100.00", "0.00");
        assertEquals(0, paymentCount());
    }
}
