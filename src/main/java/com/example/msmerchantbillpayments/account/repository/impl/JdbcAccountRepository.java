package com.example.msmerchantbillpayments.account.repository.impl;

import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountRepository implements AccountRepository {
    private final JdbcTemplate jdbc;

    public JdbcAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Account account) {
        jdbc.update("""
                INSERT INTO accounts (account_number, name, account_type, balance)
                VALUES (?, ?, ?, ?)
                """, account.accountNumber(), account.name(), account.accountType().name(), account.balance());
    }
}
