package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.TransactionResponse;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/transactions")
public class TransactionController {

    private final TransactionRepository transactionRepository;

    public TransactionController(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('TRANSACTION_READ')")
    public ResponseEntity<Page<TransactionResponse>> list(@PathVariable UUID businessId,
                                                            @RequestParam(required = false) Direction direction,
                                                            Pageable pageable) {
        Page<TransactionResponse> page = (direction == null
                ? transactionRepository.findByBusinessId(businessId, pageable)
                : transactionRepository.findByBusinessIdAndDirection(businessId, direction, pageable))
                .map(TransactionResponse::from);
        return ResponseEntity.ok(page);
    }
}
