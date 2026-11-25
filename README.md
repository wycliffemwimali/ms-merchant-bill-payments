# Merchant Bill Payments

Java 17, Spring Boot, and PostgreSQL REST API for account registration, merchant payments,
and customer mini-statements. Payments include a flat KES 30 bank fee.

## Run locally

Requires JDK 17+ (`JAVA_HOME` configured) and PostgreSQL. Create `mydb` if needed:

```bash
psql -h localhost -U postgres -d postgres -c 'CREATE DATABASE mydb;'
./mvnw spring-boot:run
```

| Environment variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mydb` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | Set through your environment |
| `SERVER_PORT` | `8080` |

Tables and additive schema changes are applied on startup without resetting balances.

Only admins register accounts. Both admins and authenticated customers can pay from any
customer account and read any customer's mini-statement. There are no account ownership
restrictions. Requests are stateless and accept JSON.

## APIs

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/api/accounts` | Register a customer or merchant account (admin only) |
| POST | `/api/payments` | Pay a merchant; requires `Idempotency-Key` |
| GET | `/api/accounts/{accountNumber}/mini-statement` | Current balance and latest five payments |

Register a customer:

```bash
curl -u admin http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC001","name":"Wycliffe Mwimali","accountType":"CUSTOMER","openingBalance":5000.00}'
```

Register a `MERCHANT` account named `MER001` before making this payment:

```bash
curl -u Wycliffe http://localhost:8080/api/payments \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: bill-001' \
  -d '{"customerAccountNumber":"ACC001","merchantAccountNumber":"MER001","amount":200.00}'
```

```bash
curl -u Wycliffe http://localhost:8080/api/accounts/ACC001/mini-statement
```

Successful payments return `201`, `status: SUCCESS`, a reference, amount, fee, total debit,
remaining balance, and timestamp. All three balances and the payment record commit together.
Failed attempts roll back and return an error code; no failed payment record is created.

Idempotency keys are scoped to the authenticated user, must be non-blank, and have a
128-character limit. Retrying the same details returns `200`, the original payment details,
and `replayed: true`; changing the details returns `409`. Use a new key for each new payment.
Keys persist in PostgreSQL, and concurrent retries cannot debit twice. Failed attempts do
not consume a key. A replay's balance is the original post-payment balance; use the statement
for the current balance.

Errors use consistent JSON: `400` invalid input, `401` missing/invalid credentials, `403`
forbidden access, `404` missing account, and `409` duplicates, insufficient funds, or conflicts.
Statements use one consistent database snapshot and return an empty list for new customers.

## Tests and Postman

```bash
./mvnw clean verify
./mvnw -Dtest=PaymentServiceUnitTest test
```

The full suite needs PostgreSQL. Integration tests create and remove isolated schemas;
application tables are untouched. Override defaults with `TEST_DB_URL`, `TEST_DB_USERNAME`,
and `TEST_DB_PASSWORD`. The test user needs schema-creation permission. Unit tests need no database.





## Structure

Features contain controllers, DTOs, models, service interfaces/implementations, and JDBC
repository interfaces/implementations. Shared errors live in `common`; Basic authentication
and role checks live in `security`. Database transactions and row locks protect balances.
