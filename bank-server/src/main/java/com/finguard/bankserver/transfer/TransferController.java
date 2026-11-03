package com.finguard.bankserver.transfer;

import com.finguard.bankserver.transfer.dto.TransferRequest;
import com.finguard.bankserver.transfer.dto.TransferResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.transfer(request);
        log.info("Transfer response requestId={} transactionId={}", response.requestId(), response.transactionId());
        return response;
    }

    @GetMapping("/{requestId}")
    public TransferResponse getTransfer(@PathVariable UUID requestId) {
        log.info("Get transfer requestId={}", requestId);
        return transferService.getTransfer(requestId);
    }
}
