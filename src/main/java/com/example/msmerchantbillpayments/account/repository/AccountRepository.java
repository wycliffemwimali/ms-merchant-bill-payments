package com.example.msmerchantbillpayments.account.repository;

import com.example.msmerchantbillpayments.account.model.Account;
import java.math.BigDecimal;
import java.util.Optional;

public interface AccountRepository {
    void insert(Account account);
    Optional<Account> findByNumberForUpdate(String accountNumber);
    void updateBalance(String accountNumber, BigDecimal balance);
}
