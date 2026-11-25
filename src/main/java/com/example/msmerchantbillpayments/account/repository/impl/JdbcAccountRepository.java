package com.example.msmerchantbillpayments.account.repository.impl;

import com.example.msmerchantbillpayments.account.model.Account;
import com.example.msmerchantbillpayments.account.model.AccountType;
import com.example.msmerchantbillpayments.account.repository.AccountRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.util.Optional;

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

    @Override
    public Optional<Account> findByNumber(String accountNumber) {
        return jdbc.query("""
                SELECT account_number, name, account_type, balance
                FROM accounts WHERE account_number = ?
                """, (row, index) -> new Account(row.getString("account_number"), row.getString("name"),
                AccountType.valueOf(row.getString("account_type")), row.getBigDecimal("balance")),
                accountNumber).stream().findFirst();
    }

    @Override
    public Optional<Account> findByNumberForUpdate(String accountNumber) {
        return jdbc.query("""
                SELECT account_number, name, account_type, balance
                FROM accounts WHERE account_number = ? FOR UPDATE
                """, (row, index) -> new Account(row.getString("account_number"), row.getString("name"),
                AccountType.valueOf(row.getString("account_type")), row.getBigDecimal("balance")),
                accountNumber).stream().findFirst();
    }

    @Override
    public void updateBalance(String accountNumber, BigDecimal balance) {
        if (jdbc.update("UPDATE accounts SET balance = ? WHERE account_number = ?", balance, accountNumber) != 1) {
            throw new IllegalStateException("Account balance update failed.");
        }
    }
}
