package com.finguard.bankserver.account.dto;

import com.finguard.bankserver.account.Account;
import com.finguard.bankserver.account.AccountStatus;
import java.math.BigDecimal;

public record AccountResponse(
        Long accountId,
        String accountNumber,
        BigDecimal balance,
        AccountStatus status
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getBalance(), account.getStatus());
    }
}
