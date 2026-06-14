package com.ganchevdimitarg.wallet;

import com.ganchevdimitarg.wallet.model.Wallet;
import com.ganchevdimitarg.wallet.repository.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class AbstractWalletIntegrationTest {

    // Singleton containers — started once per JVM, shared across all test classes.
    // The manual static-block pattern is used instead of @Container so the containers
    // survive across multiple @SpringBootTest classes. Ryuk stops them at JVM exit.
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16");

    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @Autowired
    protected WalletRepository walletRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Primary datasource (writes)
        registry.add("spring.datasource.primary.jdbc-url", postgres::getJdbcUrl);
        registry.add("spring.datasource.primary.username", postgres::getUsername);
        registry.add("spring.datasource.primary.password", postgres::getPassword);

        // Replica datasource (reads) — same container in tests
        registry.add("spring.datasource.replica.jdbc-url", postgres::getJdbcUrl);
        registry.add("spring.datasource.replica.username", postgres::getUsername);
        registry.add("spring.datasource.replica.password", postgres::getPassword);

        // Flyway — must run against the primary
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    protected Wallet createWalletWithBalance(BigDecimal balance) {
        Wallet wallet = Wallet.builder()
                .playerId(UUID.randomUUID())
                .balance(balance)
                .version(0L)
                .build();
        return walletRepository.save(wallet);
    }

    protected Wallet createWalletWithBalance(UUID playerId, BigDecimal balance) {
        Wallet wallet = Wallet.builder()
                .playerId(playerId)
                .balance(balance)
                .version(0L)
                .build();
        return walletRepository.save(wallet);
    }
}
