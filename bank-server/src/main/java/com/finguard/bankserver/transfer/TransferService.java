package com.finguard.bankserver.transfer;

import com.finguard.bankserver.account.Account;
import com.finguard.bankserver.account.AccountRepository;
import com.finguard.bankserver.account.AccountStatus;
import com.finguard.bankserver.common.ApiException;
import com.finguard.bankserver.transfer.dto.TransferRequest;
import com.finguard.bankserver.transfer.dto.TransferResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final TransferRequestLock requestLock;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = 20)
    public TransferResponse transfer(TransferRequest request) {
        // ① UUID로 만든 키를 잠금
        requestLock.acquire(request.requestId());
        // ② 해당 UUID로 처리된 이체 기록이 있는지 조회
        var previous = transferRepository.findByRequestId(request.requestId());

        if (previous.isPresent()) {
            if (!previous.get().matches(request)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "The requestId has already been used for a different transfer.");
            }
            return TransferResponse.from(previous.get());
        }
        
        // ③ 새로운 이체라면 실제 계좌 행을 잠금
        long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        long secondId = Math.max(request.fromAccountId(), request.toAccountId());
        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);
        Account from = firstId == request.fromAccountId() ? first : second;
        Account to = firstId == request.toAccountId() ? first : second;
        if (from.getStatus() != AccountStatus.ACTIVE || to.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOCKED_ACCOUNT", "Both accounts must be active.");
        }

        from.debit(request.amount());
        to.credit(request.amount());
        accountRepository.flush();
        TransferRecord record = transferRepository.saveAndFlush(new TransferRecord(request));
        return TransferResponse.from(record);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID requestId) {
        return transferRepository.findByRequestId(requestId)
                .map(TransferResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found."));
    }

    private Account lockAccount(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found."));
    }
}
