package com.finguard.bankserver.transfer.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID requestId,
        @NotNull @Positive Long fromAccountId,
        @NotNull @Positive Long toAccountId,
        @NotNull @DecimalMin("1") @Digits(integer = 19, fraction = 0) BigDecimal amount) {

    // @AssertTrue의 "Source and destination accounts must differ."가 응답에 포함되지 않음
    // handleExceptionInternal()에서 모든 400 오류를 "Invalid HTTP request."로 통일하기 때문
    @AssertTrue(message = "Source and destination accounts must differ.")
    public boolean isDifferentAccounts() {
        return fromAccountId == null || toAccountId == null || !fromAccountId.equals(toAccountId);
    }
}
