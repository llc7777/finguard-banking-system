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
        
        // 송금 방향에 관계없이 항상 작은 계좌 ID부터 잠금을 획득 (데드락 방지)
        // 두 사용자가 동시에 서로에게 송금한다고 가정
        // - 트랜잭션 A: 계좌 1 → 계좌 2
        // - 트랜잭션 B: 계좌 2 → 계좌 1
        // 서로 잠금 획득을 기다리며 데드락 발생
        long firstId = Math.min(request.fromAccountId(), request.toAccountId());
        long secondId = Math.max(request.fromAccountId(), request.toAccountId());
        // ③ 새로운 이체라면 실제 계좌 행을 잠금
        Account first = lockAccount(firstId);
        Account second = lockAccount(secondId);
        // ④ 송금방향 다시 대입
        Account from = firstId == request.fromAccountId() ? first : second;
        Account to = firstId == request.toAccountId() ? first : second;
        // ⑤ 계좌 상태 확인
        if (from.getStatus() != AccountStatus.ACTIVE || to.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "BLOCKED_ACCOUNT", "Both accounts must be active.");
        }
        // ⑥ 송금 비즈니스 로직 수행
        from.debit(request.amount());
        to.credit(request.amount());
        // JPA가 감지한 계좌 잔액 변경 사항을 UPDATE SQL로 즉시 DB에 반영
        // flush()는 저장된 변경 사항을 반영할 뿐, 트랜잭션을 COMMIT하지는 않음
        accountRepository.flush();
        // 새로운 송금 기록 엔티티를 생성하고 DB에 INSERT SQL로 반영
        // save() JPA에 저장 요청 (INSERT 대상)
        // saveAndFlush() = save() + flush(): 저장 처리 후 즉시 SQL 실행
        // 저장된 엔티티를 반환하며, 최종 확정은 트랜잭션 COMMIT 시점에 이루어짐
        TransferRecord record = transferRepository.saveAndFlush(new TransferRecord(request));
        return TransferResponse.from(record);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID requestId) {
        return transferRepository.findByRequestId(requestId)
                .map(TransferResponse::from)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found."));
    }
    // orElseThrow()는 Optional 객체에 값이 없으면 예외를 발생시키고
    // 값이 있으면 그 값을 반환
    private Account lockAccount(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found."));
    }
}
