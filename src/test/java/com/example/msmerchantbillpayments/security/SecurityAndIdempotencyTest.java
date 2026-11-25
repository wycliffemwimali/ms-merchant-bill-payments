package com.example.msmerchantbillpayments.security;

import com.example.msmerchantbillpayments.account.dto.RegisterAccountRequest;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.service.AccountService;
import com.example.msmerchantbillpayments.payment.dto.BillPaymentRequest;
import com.example.msmerchantbillpayments.payment.service.PaymentService;
import com.example.msmerchantbillpayments.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SecurityAndIdempotencyTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PaymentService paymentService;
    @Autowired private AccountService accountService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM accounts");
        jdbc.update("UPDATE bank_fee_account SET balance = 0 WHERE id = 1");
        jdbc.update("""
                INSERT INTO accounts (account_number, name, account_type, balance) VALUES
                ('C001', 'Customer one', 'CUSTOMER', 1000),
                ('C002', 'Customer two', 'CUSTOMER', 1000),
                ('C003', 'Legacy customer', 'CUSTOMER', 1000),
                ('M001', 'Merchant', 'MERCHANT', 0)
                """);
    }

    @Test
    void requiresValidCredentialsForEveryApi() throws Exception {
        for (var request : List.of(get("/api/accounts/C001/mini-statement"), post("/api/accounts"), post("/api/payments"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().exists("WWW-Authenticate"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(get("/api/accounts/C001/mini-statement").with(httpBasic("Wycliffe", "wrong")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void onlyAdminCanRegisterAccounts() throws Exception {
        String body = """
                {"accountNumber":"NEW001","name":"New Customer","accountType":"CUSTOMER",
                 "openingBalance":100}
                """;
        mvc.perform(post("/api/accounts").with(httpBasic("Wycliffe", "customer123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(post("/api/accounts").with(httpBasic("admin", "admin123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/accounts/NEW001/mini-statement").with(httpBasic("Wycliffe", "customer123")))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedCustomersCanReadAndPayFromAnyCustomerAccount() throws Exception {
        mvc.perform(get("/api/accounts/C002/mini-statement").with(httpBasic("Wycliffe", "customer123")))
                .andExpect(status().isOk());
        mvc.perform(payment("C002", "100", "shared-account")).andExpect(status().isCreated());
        assertEquals(1, count());
        assertEquals(new BigDecimal("870.00"), balance("C002"));
        mvc.perform(get("/api/accounts/C003/mini-statement").with(httpBasic("Wycliffe", "customer123")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/accounts/C003/mini-statement").with(httpBasic("admin", "admin123")))
                .andExpect(status().isOk());
    }

    @Test
    void allowsCustomersAndAdminToReadStatements() throws Exception {
        mvc.perform(get("/api/accounts/C001/mini-statement").with(httpBasic("Wycliffe", "customer123")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableBalance").value(1000));
        mvc.perform(get("/api/accounts/C002/mini-statement").with(httpBasic("admin", "admin123")))
                .andExpect(status().isOk());
    }

    @Test
    void retriesReturnOriginalReferenceAndDoNotDebitAgain() throws Exception {
        var first = mvc.perform(payment("C001", "100", "retry-1"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        var retry = mvc.perform(payment("C001", "100.00", "retry-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true)).andReturn();
        assertEquals(reference(first), reference(retry));
        assertEquals(new BigDecimal("870.00"), balance("C001"));
        assertEquals(new BigDecimal("100.00"), balance("M001"));
        assertEquals(new BigDecimal("30.00"), feeBalance());
        assertEquals(1, count());
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM payments", String.class));
    }

    @Test
    void replayPreservesOriginalResponseAfterAnotherPaymentAndSchemaRestart() throws Exception {
        var first = mvc.perform(payment("C001", "100", "first")).andExpect(status().isCreated()).andReturn();
        mvc.perform(payment("C001", "100", "second")).andExpect(status().isCreated());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        var replay = mvc.perform(payment("C001", "100", "first"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableBalance").value(870)).andReturn();
        assertEquals(reference(first), reference(replay));
        assertEquals(new BigDecimal("740.00"), balance("C001"));
        assertEquals(2, count());
    }

    @Test
    void rejectsChangedPayloadForSameKey() throws Exception {
        mvc.perform(payment("C001", "100", "same-key")).andExpect(status().isCreated());
        mvc.perform(payment("C001", "101", "same-key"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertEquals(1, count());
        assertEquals(new BigDecimal("870.00"), balance("C001"));
    }

    @Test
    void keysAreScopedToAuthenticatedUser() throws Exception {
        mvc.perform(payment("C001", "100", "shared-key")).andExpect(status().isCreated());
        mvc.perform(paymentAs("C002", "100", "shared-key", "Mwimali", "customer456"))
                .andExpect(status().isCreated());
        assertEquals(2, count());
    }

    @Test
    void sameKeyFromDifferentUsersCreatesSeparatePayments() throws Exception {
        var first = mvc.perform(payment("C001", "100", "shared-key"))
                .andExpect(status().isCreated()).andReturn();
        var second = mvc.perform(paymentAs("C001", "100", "shared-key", "Mwimali", "customer456"))
                .andExpect(status().isCreated()).andReturn();
        assertNotEquals(reference(first), reference(second));
        assertEquals(2, count());
        assertEquals(new BigDecimal("740.00"), balance("C001"));
    }

    @Test
    void requiresNonblankBoundedIdempotencyKey() throws Exception {
        mvc.perform(post("/api/payments").with(httpBasic("Wycliffe", "customer123"))
                        .contentType(MediaType.APPLICATION_JSON).content(body("C001", "100")))
                .andExpect(status().isBadRequest());
        for (String key : List.of(" ", "A".repeat(129))) {
            mvc.perform(payment("C001", "100", key)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertEquals(0, count());
    }

    @Test
    void concurrentRetriesCreateExactlyOnePayment() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        Callable<MvcResult> request = () -> {
            ready.countDown();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            return mvc.perform(payment("C001", "100", "concurrent-key")).andReturn();
        };
        try {
            var a = executor.submit(request);
            var b = executor.submit(request);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var first = a.get(15, TimeUnit.SECONDS);
            var second = b.get(15, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), java.util.stream.Stream.of(first, second)
                    .map(result -> result.getResponse().getStatus()).sorted().toList());
            assertEquals(reference(first), reference(second));
            assertEquals(1, count());
            assertEquals(new BigDecimal("870.00"), balance("C001"));
            assertEquals(new BigDecimal("30.00"), feeBalance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedAttemptDoesNotConsumeTheKey() throws Exception {
        jdbc.execute("ALTER TABLE payments ADD CONSTRAINT test_fail_insert CHECK (amount <> 100)");
        try {
            mvc.perform(payment("C001", "100", "retry-after-failure")).andExpect(status().isInternalServerError());
            assertEquals(0, count());
            assertEquals(new BigDecimal("1000.00"), balance("C001"));
            assertEquals(new BigDecimal("0.00"), balance("M001"));
            assertEquals(new BigDecimal("0.00"), feeBalance());
        } finally {
            jdbc.execute("ALTER TABLE payments DROP CONSTRAINT test_fail_insert");
        }
        mvc.perform(payment("C001", "100", "retry-after-failure")).andExpect(status().isCreated());
        assertEquals(1, count());
    }

    @Test
    void concurrentDifferentPayloadsWithSameKeyRejectOne() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(payment("C001", "100", "conflicting-key")).andReturn();
            });
            var second = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(payment("C001", "200", "conflicting-key")).andReturn();
            });
            start.countDown();
            assertEquals(List.of(201, 409), java.util.stream.Stream.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                    .map(result -> result.getResponse().getStatus()).sorted().toList());
            assertEquals(1, count());
            assertEquals(new BigDecimal("30.00"), feeBalance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void credentialsAreRequiredAgainOnTheNextRequest() throws Exception {
        mvc.perform(get("/api/accounts/C001/mini-statement").with(httpBasic("Wycliffe", "customer123")))
                .andExpect(status().isOk()).andExpect(cookie().doesNotExist("JSESSIONID"));
        mvc.perform(get("/api/accounts/C001/mini-statement"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void serviceRejectsUnauthenticatedCallers() {
        assertThrows(AuthenticationException.class, () -> paymentService.pay(
                new BillPaymentRequest("C001", "M001", new BigDecimal("100")), "direct"));
    }

    @Test
    @WithMockUser(username = "Mwimali", roles = "CUSTOMER")
    void serviceAllowsCustomerPaymentsButEnforcesAdminRegistration() {
        assertNotNull(paymentService.pay(
                new BillPaymentRequest("C001", "M001", new BigDecimal("100")), "direct").reference());
        assertThrows(AccessDeniedException.class, () -> accountService.register(
                new RegisterAccountRequest("NEW001", "New", AccountType.CUSTOMER, BigDecimal.ZERO)));
    }

    private MockHttpServletRequestBuilder payment(String customer, String amount, String key) {
        return paymentAs(customer, amount, key, "Wycliffe", "customer123");
    }

    private MockHttpServletRequestBuilder paymentAs(String customer, String amount, String key, String username, String password) {
        return post("/api/payments").with(httpBasic(username, password))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(customer, amount));
    }

    private String body(String customer, String amount) {
        return """
                {"customerAccountNumber":"%s","merchantAccountNumber":"M001","amount":%s}
                """.formatted(customer, amount);
    }

    private String reference(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString()).get("reference").asText();
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);
    }

    private BigDecimal balance(String account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = ?", BigDecimal.class, account);
    }

    private BigDecimal feeBalance() {
        return jdbc.queryForObject("SELECT balance FROM bank_fee_account", BigDecimal.class);
    }
}
