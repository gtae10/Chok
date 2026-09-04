package com.project.Chok.service;

import com.project.Chok.domain.PerformanceSnapshot;
import com.project.Chok.domain.PriceHistory;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.dto.PerformanceItemResponse;
import com.project.Chok.dto.PerformanceSummaryResponse;
import com.project.Chok.repository.PerformanceSnapshotRepository;
import com.project.Chok.repository.PriceHistoryRepository;
import com.project.Chok.repository.RecommendationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * "실전 성과 추적" - 분석 완료 시점마다 그날 종합점수 상위 10개 종목을 스냅샷으로 저장하고,
 * 조회 시점의 최신가로 진입가 대비 수익률을 계산해 보여준다. backtest.py(과거 데이터 기반
 * 시뮬레이션)와 짝을 이루는, 실시간/실전 성과 추적 담당.
 */
@Service
public class PerformanceTrackingService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceTrackingService.class);
    private static final int TOP_N = 10;

    private final RecommendationRepository recommendationRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final PerformanceSnapshotRepository performanceSnapshotRepository;

    public PerformanceTrackingService(RecommendationRepository recommendationRepository,
                                       PriceHistoryRepository priceHistoryRepository,
                                       PerformanceSnapshotRepository performanceSnapshotRepository) {
        this.recommendationRepository = recommendationRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.performanceSnapshotRepository = performanceSnapshotRepository;
    }

    /** 그날 종합점수 상위 TOP_N개 종목을 스냅샷으로 저장(같은 종목+날짜면 갱신)한다. */
    public void saveSnapshot(LocalDate date) {
        List<Recommendation> top = recommendationRepository.findByRecDateOrderByFinalScoreDesc(date);
        if (top.isEmpty()) {
            log.warn("성과 스냅샷 생략: {} 날짜의 추천 결과가 없음", date);
            return;
        }
        if (top.size() > TOP_N) {
            top = top.subList(0, TOP_N);
        }

        int savedCount = 0;
        for (int i = 0; i < top.size(); i++) {
            Recommendation rec = top.get(i);
            Optional<PriceHistory> entry = priceHistoryRepository.findByTickerAndTradeDate(rec.getTicker(), date);
            if (entry.isEmpty()) {
                log.warn("성과 스냅샷 생략 (ticker={}, date={}): 해당일 종가 없음", rec.getTicker(), date);
                continue;
            }

            PerformanceSnapshot snapshot = performanceSnapshotRepository
                    .findByTickerAndSnapshotDate(rec.getTicker(), date)
                    .orElse(new PerformanceSnapshot());

            snapshot.setTicker(rec.getTicker());
            snapshot.setName(rec.getName());
            snapshot.setSnapshotDate(date);
            snapshot.setRank(i + 1);
            snapshot.setTechnicalScore(rec.getTechnicalScore());
            snapshot.setSentimentScore(rec.getSentimentScore());
            snapshot.setFinalScore(rec.getFinalScore());
            snapshot.setRiseProbability(rec.getRiseProbability());
            snapshot.setEntryPrice(entry.get().getClosePrice());
            snapshot.setCreatedAt(LocalDateTime.now());

            performanceSnapshotRepository.save(snapshot);
            savedCount++;
        }

        log.info("성과 스냅샷 저장 완료: {} 날짜, {}건", date, savedCount);
    }

    /** 전체 스냅샷 목록 + 각각의 현재 수익률/보유일수 + 집계 통계(승률, 평균 수익률)를 계산한다. */
    public PerformanceSummaryResponse getPerformanceSummary() {
        LocalDate today = LocalDate.now();
        List<PerformanceSnapshot> snapshots = performanceSnapshotRepository.findAllOrderBySnapshotDateDescRankAsc();

        List<PerformanceItemResponse> items = snapshots.stream()
                .map(s -> {
                    Integer currentPrice = priceHistoryRepository.findLatestByTicker(s.getTicker())
                            .map(PriceHistory::getClosePrice)
                            .orElse(null);
                    return new PerformanceItemResponse(s, currentPrice, today);
                })
                .toList();

        List<Double> returns = items.stream()
                .map(PerformanceItemResponse::getReturnRate)
                .filter(r -> r != null)
                .toList();

        Double winRate = returns.isEmpty() ? null
                : round2(returns.stream().filter(r -> r > 0).count() * 100.0 / returns.size());
        Double avgReturnRate = returns.isEmpty() ? null
                : round2(returns.stream().mapToDouble(Double::doubleValue).average().orElse(0.0) * 100.0);

        return new PerformanceSummaryResponse(items.size(), winRate, avgReturnRate, items);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
