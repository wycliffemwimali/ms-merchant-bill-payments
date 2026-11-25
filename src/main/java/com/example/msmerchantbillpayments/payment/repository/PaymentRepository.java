package com.example.msmerchantbillpayments.payment.repository;

import com.example.msmerchantbillpayments.payment.model.Payment;
import java.math.BigDecimal;

public interface PaymentRepository {
    void insert(Payment payment);
    boolean creditBankFee(BigDecimal fee);
}
