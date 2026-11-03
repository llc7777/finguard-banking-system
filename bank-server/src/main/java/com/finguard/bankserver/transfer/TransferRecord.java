package com.finguard.bankserver.transfer;

import com.finguard.bankserver.transfer.dto.TransferRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transactions")
@Getter
// JPA는 엔티티를 생성할 때 public 또는 protected인 매개변수 없는 생성자가 필요
// 디폴트 생성자 프로텍트 레벨로 만듬
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, unique = true, updatable = false)
    private UUID requestId;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private Long fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private Long toAccountId;

    // precision = 19	소수점 제외 전체 숫자 자릿수
    // scale = 0	소수점 이하 자릿수 # 즉 소수점 저장 불가 최대 19자리 숫자
    @Column(nullable = false, precision = 19, scale = 0, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 16, updatable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    // Instant는 Java에서 제공하는 특정 시점을 나타내는 날짜·시간 객체
    // Instant now = Instant.now(); == 2026-11-02T05:30:00Z UTC를 기준+
    private Instant createdAt;

    public TransferRecord(TransferRequest request) {
        requestId = request.requestId();
        fromAccountId = request.fromAccountId();
        toAccountId = request.toAccountId();
        amount = request.amount().setScale(0);
        status = "SUCCESS";
        // Match PostgreSQL's microsecond resolution so a replay returns the exact original timestamp.
        createdAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    public boolean matches(TransferRequest request) {
        return fromAccountId.equals(request.fromAccountId())
                && toAccountId.equals(request.toAccountId())
                && amount.compareTo(request.amount()) == 0;
    }
}
