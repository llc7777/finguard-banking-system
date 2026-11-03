package com.finguard.bankserver.transfer;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRepository extends JpaRepository<TransferRecord, Long> {

    Optional<TransferRecord> findByRequestId(UUID requestId);
}
