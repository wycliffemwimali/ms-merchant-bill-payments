package com.example.msmerchantbillpayments.payment.repository.impl;

import com.example.msmerchantbillpayments.payment.model.Payment;
import com.example.msmerchantbillpayments.payment.model.PaymentStatus;
import com.example.msmerchantbillpayments.payment.repository.PaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.sql.ResultSet;
import java.sql.SQLException;

@Repository
public class JdbcPaymentRepository implements PaymentRepository {
    private final JdbcTemplate jdbc;

    public JdbcPaymentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Payment payment) {
        jdbc.update("""
                INSERT INTO payments (reference, customer_account_number, merchant_account_number,
                                      amount, fee, customer_balance_after, paid_at, requester, idempotency_key, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, payment.reference(), payment.customerAccountNumber(), payment.merchantAccountNumber(),
                payment.amount(), payment.fee(), payment.customerBalanceAfter(), Timestamp.from(payment.paidAt()),
                payment.requester(), payment.idempotencyKey(), payment.status().name());
    }

    @Override
    public List<Payment> findLatestFiveByCustomer(String accountNumber) {
        return jdbc.query("""
                SELECT reference, customer_account_number, merchant_account_number,
                       amount, fee, customer_balance_after, paid_at, requester, idempotency_key, status
                FROM payments WHERE customer_account_number = ?
                ORDER BY paid_at DESC, reference DESC
                LIMIT 5
                """, this::mapPayment, accountNumber);
    }

    @Override
    public void lockRequest(String requester, String idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (row, index) -> 0, requester.length() + ":" + requester + idempotencyKey);
    }

    @Override
    public Optional<Payment> findByRequest(String requester, String idempotencyKey) {
        return jdbc.query("""
                SELECT reference, customer_account_number, merchant_account_number,
                       amount, fee, customer_balance_after, paid_at, requester, idempotency_key, status
                FROM payments WHERE requester = ? AND idempotency_key = ?
                """, this::mapPayment, requester, idempotencyKey).stream().findFirst();
    }

    private Payment mapPayment(ResultSet row, int index) throws SQLException {
        return new Payment(row.getObject("reference", UUID.class), row.getString("customer_account_number"),
                row.getString("merchant_account_number"), row.getBigDecimal("amount"), row.getBigDecimal("fee"),
                row.getBigDecimal("customer_balance_after"), row.getTimestamp("paid_at").toInstant(),
                row.getString("requester"), row.getString("idempotency_key"), PaymentStatus.valueOf(row.getString("status")));
    }

    @Override
    public boolean creditBankFee(BigDecimal fee) {
        return jdbc.update("""
                UPDATE bank_fee_account SET balance = balance + ?
                WHERE id = 1 AND balance <= 99999999999999999.99 - ?
                """, fee, fee) == 1;
    }
}
