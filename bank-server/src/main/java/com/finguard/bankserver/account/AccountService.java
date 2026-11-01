package com.finguard.bankserver.account;

import com.finguard.bankserver.account.dto.AccountResponse;
import com.finguard.bankserver.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long accountId) {
        return accountRepository.findById(accountId)
                // Optional.map() → 값이 존재하면 그 값 하나를 변환 # 0개 또는 1개
                // Stream.map() → 스트림 안의 각 요소를 변환 # 여러개
                .map(AccountResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "계좌를 찾을 수 없습니다."));
    }
}
