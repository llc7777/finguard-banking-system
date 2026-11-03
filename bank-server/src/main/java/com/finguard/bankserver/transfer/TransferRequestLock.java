package com.finguard.bankserver.transfer;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
// 동일한 requestId의 송금 요청이 동시에 처리되지 않도록 PostgreSQL 잠금을 관리
public class TransferRequestLock {

    // SQL 실행을 위한 Spring JDBC 객체
    private final JdbcTemplate jdbcTemplate;

    // 기존 트랜잭션에 참여하며, 트랜잭션이 없으면 예외 발생
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID requestId) {

        // 다른 트랜잭션이 잠금을 보유 중이면 최대 5초까지 대기
        jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");

        // UUID의 상위·하위 64비트를 XOR 연산해 잠금에 사용할 long 키 생성
        // 동일한 UUID는 항상 동일한 잠금 키를 생성
        long lockKey = requestId.getMostSignificantBits()
                ^ requestId.getLeastSignificantBits();

        // PostgreSQL 내장 함수 pg_advisory_xact_lock()으로 잠금 획득
        // 전달된 숫자 키에 트랜잭션 단위 잠금을 걸어 동일한 키의 동시 실행을 방지
        // 다른 트랜잭션이 해당 키를 잠그고 있으면 해제될 때까지 대기 (최대 5초)
        // 실제 DB 행을 잠그는 것이 아니며 COMMIT 또는 ROLLBACK 시 자동 해제
        jdbcTemplate.query(
                "SELECT pg_advisory_xact_lock(?)",
                rs -> {},  // SQL 결과(함수 반환 void)는 사용하지 않으므로 무시
                lockKey    // SQL의 ?에 전달할 잠금 키
        );
    }
}