package com.finguard.bankserver.account;

import com.finguard.bankserver.common.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Entity
@Table(name = "accounts")
@Getter
public class Account {

    public static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999999999");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, length = 32)
    private String accountNumber;
    // precision = 19	소수점 제외 전체 숫자 자릿수
    // scale = 0	소수점 이하 자릿수 # 즉 소수점 저장 불가 최대 19자리 숫자
    // BigDecimal을 사용하는 이유는 금액을 정확하게 표현하고 계산하기 위해서이다.
    // double이나 float은 부동소수점 방식으로 계산하기 때문에 오차가 발생할 수 있다.
    // 예 를 들어, 0.1 + 0.2를 double로 계산하면 0.30000000000000004가 된다.
    @Column(nullable = false, precision = 19, scale = 0)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String accountNumber, BigDecimal balance, AccountStatus status) {
        if (accountNumber == null || accountNumber.isBlank() || accountNumber.length() > 32) {
            throw new IllegalArgumentException("Account number must contain between 1 and 32 characters");
        }
        this.accountNumber = accountNumber;
        this.balance = integerAmount(balance);
        if (this.balance.signum() < 0 || this.balance.compareTo(MAX_BALANCE) > 0) {
            throw new IllegalArgumentException("Balance must fit NUMERIC(19,0) and be nonnegative");
        }
        this.status = Objects.requireNonNull(status, "Account status is required");
        this.createdAt = Instant.now();
    }

    public void debit(BigDecimal amount) {
        BigDecimal debitAmount = positiveAmount(amount);
        if (balance.compareTo(debitAmount) < 0) {
            throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS", "잔액이 부족합니다.");
        }
        balance = balance.subtract(debitAmount);
    }

    public void credit(BigDecimal amount) {
        BigDecimal creditedBalance = balance.add(positiveAmount(amount));
        if (creditedBalance.compareTo(MAX_BALANCE) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "BALANCE_LIMIT_EXCEEDED", "입금 계좌의 잔액 한도를 초과합니다.");
        }
        balance = creditedBalance;
    }

    private static BigDecimal positiveAmount(BigDecimal amount) {
        BigDecimal integerAmount = integerAmount(amount);
        if (integerAmount.signum() <= 0 || integerAmount.compareTo(MAX_BALANCE) > 0) {
            throw new IllegalArgumentException("Amount must be positive and fit NUMERIC(19,0)");
        }
        return integerAmount;
    }

    private static BigDecimal integerAmount(BigDecimal amount) {
        return Objects.requireNonNull(amount, "Amount is required").setScale(0, RoundingMode.UNNECESSARY);
    }
}
