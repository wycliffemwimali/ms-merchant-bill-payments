package com.example.msmerchantbillpayments.payment.repository;

import com.example.msmerchantbillpayments.payment.model.Payment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {
    void insert(Payment payment);
    boolean creditBankFee(BigDecimal fee);
    List<Payment> findLatestFiveByCustomer(String accountNumber);
    void lockRequest(String requester, String idempotencyKey);
    Optional<Payment> findByRequest(String requester, String idempotencyKey);
}
