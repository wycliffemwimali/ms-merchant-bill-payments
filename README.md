# Merchant Bill Payments

REST API built with Java 17, Spring Boot, and PostgreSQL for a merchant bill payments assessment.
Account registration is implemented; bill payments and mini-statements are in progress.

## Getting started

Requires JDK 17+ (`JAVA_HOME` configured) and PostgreSQL. Maven is included via the wrapper.

Create the database if it does not exist:

```bash
psql -h localhost -U postgres -d postgres -c 'CREATE DATABASE mydb;'
```

Configure the connection using environment variables, or use the local defaults:

| Variable | Default |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/mydb` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | `postgres` |
| `SERVER_PORT` | `8080` |

Start the application:

```bash
./mvnw spring-boot:run
```

The API runs at `http://localhost:8080`. Tables are created automatically on startup.

## Account registration

`POST /api/accounts` registers a `CUSTOMER` or `MERCHANT` account. Amounts are in KES.

```bash
curl -i http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"accountNumber":"ACC001","name":"Wycliffe Mwimali","accountType":"CUSTOMER","openingBalance":5000.00}'
```

Returns `201 Created` with the account details and balance, `400 Bad Request` for
invalid input, or `409 Conflict` for an existing account number.

## Testing

```bash
./mvnw clean verify
```

Tests require PostgreSQL and use temporary schemas, leaving application tables untouched.
They default to the connection settings above. Override with `TEST_DB_URL`,
`TEST_DB_USERNAME`, and `TEST_DB_PASSWORD`; the test user needs permission to create schemas.

## Structure

Code is organized by feature into controllers, DTOs, models, services, repositories,
and exceptions. Controllers depend on service interfaces; service implementations
use repository interfaces backed by JDBC. Shared error handling lives in `common`.
