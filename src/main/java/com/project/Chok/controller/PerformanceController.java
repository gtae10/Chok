package com.project.Chok.controller;

import com.project.Chok.dto.PerformanceSummaryResponse;
import com.project.Chok.service.PerformanceTrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PerformanceController {

    private final PerformanceTrackingService performanceTrackingService;

    public PerformanceController(PerformanceTrackingService performanceTrackingService) {
        this.performanceTrackingService = performanceTrackingService;
    }

    @GetMapping("/performance")
    public ResponseEntity<PerformanceSummaryResponse> getPerformance() {
        return ResponseEntity.ok(performanceTrackingService.getPerformanceSummary());
    }
}
