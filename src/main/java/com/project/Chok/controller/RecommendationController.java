package com.project.Chok.controller;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.PriceHistoryResponse;
import com.project.Chok.dto.RecommendationResponse;
import com.project.Chok.domain.NewsSentiment;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.repository.NewsSentimentRepository;
import com.project.Chok.repository.PriceHistoryRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.service.AnalysisStatus;
import com.project.Chok.service.DataCollectionService;
import com.project.Chok.service.RecommendationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class RecommendationController {

    private final RecommendationRepository recommendationRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final NewsSentimentRepository newsSentimentRepository;
    private final DataCollectionService dataCollectionService;
    private final RecommendationService recommendationService;
    private final AnalysisStatus analysisStatus;
    private final AppProperties appProperties;

    public RecommendationController(RecommendationRepository recommendationRepository,
                                    PriceHistoryRepository priceHistoryRepository,
                                    NewsSentimentRepository newsSentimentRepository,
                                    DataCollectionService dataCollectionService,
                                    RecommendationService recommendationService,
                                    AnalysisStatus analysisStatus,
                                    AppProperties appProperties) {
        this.recommendationRepository = recommendationRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.newsSentimentRepository = newsSentimentRepository;
        this.dataCollectionService = dataCollectionService;
        this.recommendationService = recommendationService;
        this.analysisStatus = analysisStatus;
        this.appProperties = appProperties;
    }

    @PostMapping("/collection/run")
    public ResponseEntity<Map<String, Object>> runCollection() {
        try {
            String output = dataCollectionService.runCollection();
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "output", output
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "failed",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/analysis/run")
    public ResponseEntity<Map<String, Object>> runAnalysis() {
        boolean started = analysisStatus.tryStart("manual");
        if (!started) {
            return ResponseEntity.status(409).body(Map.of(
                    "status", "already_running"
            ));
        }

        Thread worker = new Thread(() -> {
            try {
                int processed = recommendationService.runFullAnalysis(analysisStatus);
                analysisStatus.markDone(processed);
            } catch (Exception e) {
                analysisStatus.markFailed(e.getMessage());
            }
        }, "analysis-runner");
        worker.setDaemon(true);
        worker.start();

        return ResponseEntity.accepted().body(Map.of(
                "status", "started"
        ));
    }

    @GetMapping("/analysis/status")
    public ResponseEntity<Map<String, Object>> getAnalysisStatus() {
        Map<String, Object> body = new HashMap<>();
        body.put("phase", analysisStatus.getPhase().name());
        body.put("running", analysisStatus.isRunning());
        body.put("processedCount", analysisStatus.getProcessedCount());
        body.put("totalCount", analysisStatus.getTotalCount());
        body.put("errorMessage", analysisStatus.getErrorMessage());
        body.put("triggeredBy", analysisStatus.getTriggeredBy());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/recommendations")
    public ResponseEntity<List<RecommendationResponse>> getRecommendations(
            @RequestParam(required = false) String filter
    ) {
        LocalDate latestDate = recommendationRepository.findLatestRecDate();
        if (latestDate == null) return ResponseEntity.ok(List.of());

        List<Recommendation> recs = recommendationRepository
                .findByRecDateOrderByFinalScoreDesc(latestDate);

        if (filter != null && !filter.isBlank()) {
            recs = recs.stream()
                    .filter(r -> r.getRecommendation().equals(filter.toUpperCase()))
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(
                recs.stream().map(RecommendationResponse::new).collect(Collectors.toList())
        );
    }

    @GetMapping("/stocks/{ticker}/prices")
    public ResponseEntity<List<PriceHistoryResponse>> getPrices(@PathVariable String ticker) {
        List<PriceHistory> prices = priceHistoryRepository.findByTickerOrderByTradeDateAsc(ticker);
        return ResponseEntity.ok(
                prices.stream().map(PriceHistoryResponse::new).collect(Collectors.toList())
        );
    }

    @GetMapping("/stocks/{ticker}/news")
    public ResponseEntity<List<NewsSentiment>> getNews(@PathVariable String ticker) {
        int lookbackDays = appProperties.getAnalysis().getNewsLookbackDays();
        LocalDate fromDate = LocalDate.now().minusDays(Math.max(lookbackDays, 1));
        return ResponseEntity.ok(newsSentimentRepository.findByTickerSince(ticker, fromDate));
    }

    @GetMapping("/stocks/{ticker}/history")
    public ResponseEntity<List<RecommendationResponse>> getHistory(@PathVariable String ticker) {
        return ResponseEntity.ok(
                recommendationRepository.findHistoryByTicker(ticker)
                        .stream().map(RecommendationResponse::new).collect(Collectors.toList())
        );
    }
}
