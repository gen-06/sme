package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.DataSourceCreateRequest;
import com.creditscore.platform.api.dto.DataSourceResponse;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.DataSourceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/data-sources")
public class DataSourceController {

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('DATA_SOURCE_WRITE')")
    public ResponseEntity<DataSourceResponse> create(@PathVariable UUID businessId,
                                                       @Valid @RequestBody DataSourceCreateRequest request) {
        DataSource dataSource = dataSourceService.create(businessId, request.adapterType(), request.provider());
        return ResponseEntity.status(HttpStatus.CREATED).body(DataSourceResponse.from(dataSource));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_SOURCE_WRITE')")
    public ResponseEntity<List<DataSourceResponse>> list(@PathVariable UUID businessId) {
        List<DataSourceResponse> response = dataSourceService.listForBusiness(businessId).stream()
                .map(DataSourceResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}
