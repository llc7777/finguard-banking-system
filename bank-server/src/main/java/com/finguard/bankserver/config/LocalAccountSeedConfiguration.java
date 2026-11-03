package com.finguard.bankserver.config;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration(proxyBeanMethods = false)
// Profile 어노테이션은 특정 프로파일이 활성화되었을 때만 Bean을 등록하도록 함
@Profile("local")
// 특정 값이 조건에 맞을 때만 Spring Bean을 등록하도록 하는 어노테이션
@ConditionalOnProperty(prefix = "bank.local", name = "seed-accounts", havingValue = "true")
public class LocalAccountSeedConfiguration {
    @Bean
    ApplicationRunner localAccountSeed(DataSource dataSource) {
        return arguments -> new ResourceDatabasePopulator(
                new ClassPathResource("db/local/seed-accounts.sql")
        ).execute(dataSource);
    }
}
