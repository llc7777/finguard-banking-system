package com.finguard.bankserver.transfer.dto;

import com.finguard.bankserver.transfer.TransferRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransferResponse(Long transactionId, UUID requestId, String status, BigDecimal amount, Instant createdAt) {

    public static TransferResponse from(TransferRecord record) {
        return new TransferResponse(record.getId(), record.getRequestId(), record.getStatus(),
                record.getAmount(), record.getCreatedAt());
    }
}
