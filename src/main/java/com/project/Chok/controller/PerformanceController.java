package com.project.Chok.controller;

import com.project.Chok.dto.PerformanceSummaryResponse;
import com.project.Chok.dto.RecommendationResponse;
import com.project.Chok.service.PerformanceTrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

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

    // 스냅샷 하나의 선정일~오늘 지표 추이 - 목록 응답에 다 얹으면 무거워지므로 개별 조회로 분리
    @GetMapping("/performance/{id}/trend")
    public ResponseEntity<List<RecommendationResponse>> getPerformanceTrend(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(performanceTrackingService.getTrend(id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
