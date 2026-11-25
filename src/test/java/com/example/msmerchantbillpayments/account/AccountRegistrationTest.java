package com.example.msmerchantbillpayments.account;

import com.example.msmerchantbillpayments.account.dto.RegisterAccountRequest;
import com.example.msmerchantbillpayments.account.exception.DuplicateAccountException;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.service.AccountService;
import com.example.msmerchantbillpayments.support.PostgresTestConfiguration;
import org.springframework.context.annotation.Import;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AccountRegistrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM accounts");
    }

    @ParameterizedTest
    @EnumSource(AccountType.class)
    void registersAndPersistsBothAccountTypes(AccountType type) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(type.name(), "5000.25")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accountNumber").value("ACC001"))
                .andExpect(jsonPath("$.name").value("Wycliffe Mwimali"))
                .andExpect(jsonPath("$.accountType").value(type.name()))
                .andExpect(jsonPath("$.balance").value(5000.25))
                .andExpect(jsonPath("$.currency").value("KES"));
        assertEquals(new BigDecimal("5000.25"), balance());
        assertEquals(type.name(), jdbc.queryForObject("SELECT account_type FROM accounts", String.class));
        assertEquals("Wycliffe Mwimali", jdbc.queryForObject("SELECT name FROM accounts", String.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.01", "99999999999999999.99"})
    void acceptsValidBalanceBoundaries(String amount) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("CUSTOMER", amount)))
                .andExpect(status().isCreated());
        assertEquals(new BigDecimal(amount).setScale(2), balance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.001", "100000000000000000.00", "null"})
    void rejectsInvalidBalancesWithoutSaving(String amount) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("CUSTOMER", amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.openingBalance").exists());
        assertEquals(0, count());
    }

    @Test
    void rejectsMissingFields() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumber").exists())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.accountType").exists())
                .andExpect(jsonPath("$.errors.openingBalance").exists());
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "AB", "ACC 001", "-ACC001",
            "123456789012345678901234567890123" + "45"})
    void rejectsInvalidAccountNumbers(String number) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("CUSTOMER", "100").replace("ACC001", number)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accountNumber").exists());
        assertEquals(0, count());
    }

    @Test
    void rejectsBlankAndOverlongNames() throws Exception {
        for (String name : new String[]{"   ", "x".repeat(121)}) {
            mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                            .content(payload("CUSTOMER", "100").replace("  Wycliffe Mwimali  ", name)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.name").exists());
        }
        assertEquals(0, count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BANK", "customer", "UNKNOWN"})
    void rejectsUnsupportedAccountTypes(String type) throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload(type, "100")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"));
        assertEquals(0, count());
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.timestamp").exists());
        assertEquals(0, count());
    }

    @Test
    void duplicateDoesNotOverwriteExistingAccount() throws Exception {
        service.register(new RegisterAccountRequest("ACC001", "Original", AccountType.CUSTOMER,
                new BigDecimal("100.00")));
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(payload("MERCHANT", "999")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_ALREADY_EXISTS"));
        assertEquals(1, count());
        assertEquals(new BigDecimal("100.00"), balance());
        assertEquals("Original", jdbc.queryForObject("SELECT name FROM accounts", String.class));
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneAccount() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> register = () -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
                service.register(new RegisterAccountRequest("ACC001", "Customer", AccountType.CUSTOMER,
                        new BigDecimal("100.00")));
                return true;
            } catch (DuplicateAccountException ex) {
                return false;
            }
        };
        try {
            var first = executor.submit(register);
            var second = executor.submit(register);
            start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, count());
            assertEquals(new BigDecimal("100.00"), balance());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serviceAlsoValidatesInputs() {
        assertThrows(ConstraintViolationException.class, () -> service.register(
                new RegisterAccountRequest("ACC001", "Customer", AccountType.CUSTOMER,
                        new BigDecimal("-1"))));
        assertEquals(0, count());
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE account_number = 'ACC001'", BigDecimal.class);
    }

    private String payload(String type, String amount) {
        return """
                {"accountNumber":"ACC001","name":"  Wycliffe Mwimali  ",
                 "accountType":"%s","openingBalance":%s}
                """.formatted(type, amount);
    }
}
