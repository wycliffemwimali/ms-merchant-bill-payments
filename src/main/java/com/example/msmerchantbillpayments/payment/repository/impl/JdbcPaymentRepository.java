package com.example.msmerchantbillpayments.payment.repository.impl;

import com.example.msmerchantbillpayments.payment.model.Payment;
import com.example.msmerchantbillpayments.payment.repository.PaymentRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;

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
                                      amount, fee, customer_balance_after, paid_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, payment.reference(), payment.customerAccountNumber(), payment.merchantAccountNumber(),
                payment.amount(), payment.fee(), payment.customerBalanceAfter(), Timestamp.from(payment.paidAt()));
    }

    @Override
    public boolean creditBankFee(BigDecimal fee) {
        return jdbc.update("""
                UPDATE bank_fee_account SET balance = balance + ?
                WHERE id = 1 AND balance <= 99999999999999999.99 - ?
                """, fee, fee) == 1;
    }
}
