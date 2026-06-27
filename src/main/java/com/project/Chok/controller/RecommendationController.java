package com.project.Chok.controller;

import com.project.Chok.domain.NewsSentiment;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.dto.PriceHistoryResponse;
import com.project.Chok.dto.RecommendationResponse;
import com.project.Chok.repository.NewsSentimentRepository;
import com.project.Chok.repository.PriceHistoryRepository;
import com.project.Chok.repository.RecommendationRepository;
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

    public RecommendationController(RecommendationRepository recommendationRepository,
                                    PriceHistoryRepository priceHistoryRepository,
                                    NewsSentimentRepository newsSentimentRepository,
                                    DataCollectionService dataCollectionService,
                                    RecommendationService recommendationService) {
        this.recommendationRepository = recommendationRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.newsSentimentRepository = newsSentimentRepository;
        this.dataCollectionService = dataCollectionService;
        this.recommendationService = recommendationService;
    }

    // ① 시세 수집 버튼
    @PostMapping("/collection/run")
    public ResponseEntity<Map<String, Object>> runCollection() {
        try {
            String output = dataCollectionService.runCollection();
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("output", output != null ? output : "");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "failed");
            result.put("message", e.getMessage() != null ? e.getMessage() : "알 수 없는 오류");
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ② 분석 실행 버튼
    @PostMapping("/analysis/run")
    public ResponseEntity<Map<String, Object>> runAnalysis() {
        try {
            int processed = recommendationService.runFullAnalysis();
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("processedCount", processed);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "failed");
            result.put("message", e.getMessage() != null ? e.getMessage() : "알 수 없는 오류");
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // 최신 추천 목록
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

    // 종목별 가격 히스토리
    @GetMapping("/stocks/{ticker}/prices")
    public ResponseEntity<List<PriceHistoryResponse>> getPrices(@PathVariable String ticker) {
        List<PriceHistory> prices = priceHistoryRepository.findByTickerOrderByTradeDateAsc(ticker);
        return ResponseEntity.ok(
                prices.stream().map(PriceHistoryResponse::new).collect(Collectors.toList())
        );
    }

    // 종목별 뉴스 + 감성분석 결과
    @GetMapping("/stocks/{ticker}/news")
    public ResponseEntity<List<NewsSentiment>> getNews(@PathVariable String ticker) {
        LocalDate fromDate = LocalDate.now().minusDays(14);
        return ResponseEntity.ok(newsSentimentRepository.findByTickerSince(ticker, fromDate));
    }

    // 종목별 추천 히스토리
    @GetMapping("/stocks/{ticker}/history")
    public ResponseEntity<List<RecommendationResponse>> getHistory(@PathVariable String ticker) {
        return ResponseEntity.ok(
                recommendationRepository.findHistoryByTicker(ticker)
                        .stream().map(RecommendationResponse::new).collect(Collectors.toList())
        );
    }
}