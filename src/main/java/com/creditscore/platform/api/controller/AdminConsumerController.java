package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.ConsumerProvisionRequest;
import com.creditscore.platform.api.dto.ConsumerProvisionResponse;
import com.creditscore.platform.api.dto.ConsumerStatusResponse;
import com.creditscore.platform.api.dto.ConsumerStatusUpdateRequest;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/consumers")
public class AdminConsumerController {

    private final ConsumerProvisioningService provisioningService;

    public AdminConsumerController(ConsumerProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<ConsumerProvisionResponse> provision(@Valid @RequestBody ConsumerProvisionRequest request) {
        var provisioned = provisioningService.provision(request.name(), request.contactEmail(), request.scopes());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ConsumerProvisionResponse(provisioned.consumerId(), provisioned.clientId(),
                        provisioned.clientSecret()));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ConsumerStatusResponse> updateStatus(@PathVariable UUID id,
            @Valid @RequestBody ConsumerStatusUpdateRequest request) {
        Consumer updated = provisioningService.updateStatus(id, request.status());
        return ResponseEntity.ok(new ConsumerStatusResponse(updated.getId(), updated.getStatus()));
    }
}
