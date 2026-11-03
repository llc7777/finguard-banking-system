package com.finguard.bankserver.account;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// Account 엔티티를 관리하고, 기본키 타입은 Long이라는 뜻
public interface AccountRepository extends JpaRepository<Account, Long> {

    // PESSIMISTIC_WRITE	DB에 쓰기 목적의 잠금 요청
    // PESSIMISTIC_READ	    DB에 읽기 목적의 잠금 요청
    // findByIdForUpdate는 출금이나 입금과 같이 계좌의 잔액을 변경하는 작업에서 사용
    // @Lock을 사용하면 JPA 구현체가 데이터베이스에 맞는 잠금 SQL을 생성
    // DB 성능을 고려한 것도 맞지만, 더 정확히는 동시성 제어와 성능을 동시에 고려해서 일반 조회와 잠금 조회를 분리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // SQL 쿼리에서 :accountId가 파라미터 이름과 일치하면
    // @Param 생략 가능하지만, 명시적으로 작성하는 것이 가독성에 좋음
    @Query("select account from Account account where account.id = :accountId")
    // @Param("accountId") 은 jpa query에서 :accountId라는 이름으로 매개변수를 전달하기 위해 사용
    Optional<Account> findByIdForUpdate(@Param("accountId") Long accountId);
}
