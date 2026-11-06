package com.finguard.bankserver;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=20", "logging.level.com.finguard=warn"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class PostgresIntegrationTestSupport {

    private static PostgreSQLContainer postgres;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JsonMapper jsonMapper;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        // 테스트용 PostgreSQL 컨테이너를 사용하여 임시 데이터베이스를 생성
        // PostgreSQLContainer는 임의로 포트, 사용자 이름, 비밀번호를 생성하여 테스트 환경을 격리
        postgres = new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("finguard_test");
        postgres.start();
        // application.yml에 정의된 spring.datasource.url, username, password를 동적으로 설정
        // add함수가 Spring이 메모리에서 관리하는 설정값에 정보를 추가 즉 DB_URL에 값이 들어감
        properties.add("spring.datasource.url", postgres::getJdbcUrl);
        properties.add("spring.datasource.username", postgres::getUsername);
        properties.add("spring.datasource.password", postgres::getPassword);
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE transactions, accounts RESTART IDENTITY CASCADE");
    }

    protected long account(String number, String balance) {
        return account(number, balance, "ACTIVE");
    }

    protected long account(String number, String balance, String status) {
        // queryForObject 메서드는 SQL 쿼리를 실행하고 결과를 단일 객체로 반환
        // Long.class는 반환 타입을 Long으로 지정, number, balance, status는 SQL 쿼리의 ? 자리로 매핑되어 전달
        return jdbc.queryForObject("""
                INSERT INTO accounts (account_number, balance, status, created_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP) RETURNING id
                """, Long.class, number, new BigDecimal(balance), status);
    }

    protected BigDecimal balance(long id) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class, id);
    }

    protected long transactionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM transactions", Long.class);
    }

    // transfer 메서드는 MockMvc를 사용하여 POST 요청을 생성
    // .formatted()는 자리들에 실제 값을 채워주는 Java String 메서드
    // 실제 테스트에선 perform() 메서드를 사용하여 이 요청을 실행하고 결과를 검증
    // JSON에는 UUID라는 자료형이 없기 때문에 String으로 표현
    protected MockHttpServletRequestBuilder transfer(UUID id, long from, long to, String amount) {
        return post("/api/transfers").contentType(APPLICATION_JSON).content("""
                {"requestId":"%s","fromAccountId":%d,"toAccountId":%d,"amount":%s}
                """.formatted(id, from, to, amount));
    }

}
