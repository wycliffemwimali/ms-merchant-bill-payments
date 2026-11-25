package com.example.msmerchantbillpayments.support;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {
    @Bean(destroyMethod = "close")
    DataSource dataSource(Environment environment) throws SQLException {
        return new IsolatedPostgresDataSource(
                environment.getRequiredProperty("spring.datasource.url"),
                environment.getRequiredProperty("spring.datasource.username"),
                environment.getRequiredProperty("spring.datasource.password"));
    }

    private static final class IsolatedPostgresDataSource extends HikariDataSource {
        private final String testSchema = "merchant_test_" + UUID.randomUUID().toString().replace("-", "");
        private boolean closed;

        private IsolatedPostgresDataSource(String url, String username, String password) throws SQLException {
            setJdbcUrl(url);
            setUsername(username);
            setPassword(password);
            setDriverClassName("org.postgresql.Driver");
            setSchema(testSchema);
            try (var connection = DriverManager.getConnection(url, username, password);
                 var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + testSchema);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            super.close();
            try (var connection = DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword());
                 var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + testSchema + " CASCADE");
                closed = true;
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not remove test schema " + testSchema, exception);
            }
        }
    }
}
