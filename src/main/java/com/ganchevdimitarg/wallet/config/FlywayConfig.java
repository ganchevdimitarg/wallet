package com.ganchevdimitarg.wallet.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Explicit Flyway configuration — needed because DataSourceConfig defines
 * multiple DataSource beans (primary + replica + routing), which causes
 * Spring Boot's Flyway auto-configuration (@ConditionalOnSingleCandidate)
 * to back off. We run migrations only against the primary.
 */
@Configuration
public class FlywayConfig {

    @Bean(initMethod = "migrate")
    public Flyway flyway(@Qualifier("primaryDataSource") DataSource primaryDataSource) {
        return Flyway.configure()
                .dataSource(primaryDataSource)
                .locations("classpath:db/migration")
                .load();
    }
}
