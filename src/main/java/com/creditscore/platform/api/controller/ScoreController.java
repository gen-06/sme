package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.ScoreResponse;
import com.creditscore.platform.scoring.ScoringService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/score")
public class ScoreController {

    private final ScoringService scoringService;

    public ScoreController(ScoringService scoringService) {
        this.scoringService = scoringService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCORE_READ')")
    public ResponseEntity<ScoreResponse> latest(@PathVariable UUID businessId) {
        return ResponseEntity.ok(ScoreResponse.from(scoringService.getLatestOrThrow(businessId)));
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('SCORE_READ')")
    public ResponseEntity<List<ScoreResponse>> history(@PathVariable UUID businessId) {
        List<ScoreResponse> response = scoringService.getHistory(businessId).stream()
                .map(ScoreResponse::from)
                .toList();
        return ResponseEntity.ok(response);
    }
}
