CREATE TABLE IF NOT EXISTS accounts (
    account_number VARCHAR(32) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    account_type VARCHAR(16) NOT NULL CHECK (account_type IN ('CUSTOMER', 'MERCHANT')),
    balance DECIMAL(19, 2) NOT NULL CHECK (balance >= 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bank_fee_account (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    balance DECIMAL(19, 2) NOT NULL CHECK (balance >= 0)
);

INSERT INTO bank_fee_account (id, balance) VALUES (1, 0.00)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS payments (
    reference UUID PRIMARY KEY,
    customer_account_number VARCHAR(32) NOT NULL REFERENCES accounts(account_number),
    merchant_account_number VARCHAR(32) NOT NULL REFERENCES accounts(account_number),
    amount DECIMAL(19, 2) NOT NULL CHECK (amount > 0),
    fee DECIMAL(19, 2) NOT NULL CHECK (fee = 30.00),
    customer_balance_after DECIMAL(19, 2) NOT NULL CHECK (customer_balance_after >= 0),
    paid_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (customer_account_number <> merchant_account_number)
);
