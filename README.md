# Merchant Bill Payments

Spring Boot backend for the Merchant Bill Payments practical assessment.

## Requirements

- JDK 17 or newer; set `JAVA_HOME` to that JDK.
- Maven is supplied through the Maven wrapper.

## Run

```bash
./mvnw spring-boot:run
```

The HTTP server uses port 8080 (override with `SERVER_PORT`). The local H2 database
persists under `data/`, which is excluded from Git. `DB_PASSWORD` optionally sets
the local database password. This configuration is for the interview demo.

## Verify

```bash
./mvnw clean verify
```

Tests use a separate in-memory H2 database and do not modify local application data.

## Implementation sequence

1. Project setup: web, validation, JDBC, database configuration, and startup test.
2. Account registration: persistence, validation, and consistent API errors.
3. Bill payment: atomic customer debit, merchant credit, and KES 30 bank fee;
   unique payment reference and concurrent payment protection.
4. Mini-statement: latest five customer transactions and available balance.
5. Submission: complete API documentation and a Postman collection.

Each completed step will be tested and committed to the `develop` branch.
The API endpoints are not implemented yet.
