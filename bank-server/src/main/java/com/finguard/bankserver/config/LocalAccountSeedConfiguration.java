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

// 아래 코드를 람다식으로 변경하여 간단하게 표현 가능
// Java에서는 new로 생성한 객체를 변수에 저장하지 않고 바로 메서드를 호출할 수 있다
//    @Bean
//    ApplicationRunner localAccountSeed(DataSource dataSource) {
//
//        return new ApplicationRunner() {
//
//            @Override
//            public void run(ApplicationArguments arguments) {
//
//                new ResourceDatabasePopulator(
//                        new ClassPathResource("db/local/seed-accounts.sql")
//                ).execute(dataSource);
//
//            }
//        };
//    }
}
