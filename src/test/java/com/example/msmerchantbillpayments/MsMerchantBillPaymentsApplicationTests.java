package com.example.msmerchantbillpayments;

import com.example.msmerchantbillpayments.support.PostgresTestConfiguration;
import org.springframework.context.annotation.Import;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class MsMerchantBillPaymentsApplicationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        assertTrue(jdbc.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL"));
        assertTrue(jdbc.queryForObject("SELECT current_schema()", String.class).startsWith("merchant_test_"));
    }

}
