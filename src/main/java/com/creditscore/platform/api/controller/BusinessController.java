package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.BusinessRegistrationRequest;
import com.creditscore.platform.api.dto.BusinessResponse;
import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.identity.business.BusinessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses")
public class BusinessController {

    private final BusinessService businessService;

    public BusinessController(BusinessService businessService) {
        this.businessService = businessService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('BUSINESS_WRITE')")
    public ResponseEntity<BusinessResponse> register(@Valid @RequestBody BusinessRegistrationRequest request) {
        Business business = businessService.register(
                request.name(), request.country(), request.industry(),
                request.registrationNumber(), request.registrationDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(BusinessResponse.from(business));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('BUSINESS_WRITE')")
    public ResponseEntity<org.springframework.data.domain.Page<BusinessResponse>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String search,
            @org.springframework.data.web.PageableDefault(sort = "name") org.springframework.data.domain.Pageable pageable) {
        var page = (search == null || search.isBlank())
                ? businessService.list(pageable)
                : businessService.search(search, pageable);
        return ResponseEntity.ok(page.map(BusinessResponse::from));
    }

    @GetMapping("/{businessId}")
    @PreAuthorize("hasAuthority('BUSINESS_WRITE')")
    public ResponseEntity<BusinessResponse> get(@PathVariable UUID businessId) {
        return ResponseEntity.ok(BusinessResponse.from(businessService.getOrThrow(businessId)));
    }
}
