package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.*;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.dto.TechnicalIndicatorResult;
import com.project.Chok.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final StockRepository stockRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final TechnicalScoreRepository technicalScoreRepository;
    private final NewsSentimentRepository newsSentimentRepository;
    private final RecommendationRepository recommendationRepository;

    private final TechnicalAnalysisService technicalAnalysisService;
    private final NewsCollectorService newsCollectorService;
    private final SentimentAnalysisService sentimentAnalysisService;

    private final AppProperties appProperties;

    public RecommendationService(StockRepository stockRepository,
                                 PriceHistoryRepository priceHistoryRepository,
                                 TechnicalScoreRepository technicalScoreRepository,
                                 NewsSentimentRepository newsSentimentRepository,
                                 RecommendationRepository recommendationRepository,
                                 TechnicalAnalysisService technicalAnalysisService,
                                 NewsCollectorService newsCollectorService,
                                 SentimentAnalysisService sentimentAnalysisService,
                                 AppProperties appProperties) {
        this.stockRepository = stockRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.technicalScoreRepository = technicalScoreRepository;
        this.newsSentimentRepository = newsSentimentRepository;
        this.recommendationRepository = recommendationRepository;
        this.technicalAnalysisService = technicalAnalysisService;
        this.newsCollectorService = newsCollectorService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        this.appProperties = appProperties;
    }

    public int runFullAnalysis() {
        return runFullAnalysis(null);
    }

    /**
     * 종목별 분석을 병렬로 실행한다. 뉴스 감성분석이 종목당 최대 newsPerStock번의
     * 블로킹 API 호출을 포함하므로, 순차 실행 시 종목 수 x 뉴스 수 만큼 직렬로 대기해야 했음.
     * 스레드풀로 동시에 여러 종목을 처리해 전체 소요 시간을 줄인다.
     * (동시성 상한은 chok.analysis.parallelism 로 조절 — API 레이트리밋 고려해서 너무 크게 잡지 말 것)
     */
    public int runFullAnalysis(AnalysisStatus status) {
        List<Stock> stocks = stockRepository.findAllOrderByMarketCapDesc();
        LocalDate today = LocalDate.now();
        int total = stocks.size();
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        int poolSize = Math.max(1, appProperties.getAnalysis().getParallelism());
        log.info("전체 분석 시작: {}개 종목, 병렬도={}", total, poolSize);
        if (status != null) status.updateProgress(0, total);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<Void>> futures = stocks.stream()
                    .map(stock -> CompletableFuture.runAsync(() -> {
                        try {
                            analyzeStock(stock, today);
                            processed.incrementAndGet();
                        } catch (Exception e) {
                            log.error("종목 분석 실패 (ticker={}): {}", stock.getTicker(), e.getMessage());
                        } finally {
                            int done = completed.incrementAndGet();
                            if (status != null) status.updateProgress(done, total);
                        }
                    }, executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        log.info("전체 분석 완료: {}/{} 종목", processed.get(), total);
        return processed.get();
    }

    @Transactional
    public void analyzeStock(Stock stock, LocalDate today) {
        String ticker = stock.getTicker();

        List<PriceHistory> prices = priceHistoryRepository.findByTickerOrderByTradeDateAsc(ticker);
        TechnicalIndicatorResult techResult = technicalAnalysisService.analyze(prices);
        saveTechnicalScore(ticker, today, techResult);

        double avgSentiment = analyzeNewsSentiment(stock, today);

        AppProperties.Analysis analysis = appProperties.getAnalysis();
        double finalScore = (techResult.getTechnicalScore() * analysis.getWeightTechnical())
                + (sentimentToScale100(avgSentiment) * analysis.getWeightSentiment());
        finalScore = Math.max(0, Math.min(100, finalScore));

        String recommendation = toRecommendation(finalScore);
        String reason = buildReason(techResult, avgSentiment);

        saveRecommendation(stock, today, techResult.getTechnicalScore(),
                avgSentiment, finalScore, techResult.getRiseProbability(),
                techResult.getProbabilitySource(), techResult.getProbabilityHorizonDays(),
                recommendation, reason);
    }

    private void saveTechnicalScore(String ticker, LocalDate today, TechnicalIndicatorResult r) {
        TechnicalScore entity = technicalScoreRepository
                .findByTickerAndCalcDate(ticker, today)
                .orElse(new TechnicalScore());

        entity.setTicker(ticker);
        entity.setCalcDate(today);
        entity.setMa5(r.getMa5());
        entity.setMa20(r.getMa20());
        entity.setMa60(r.getMa60());
        entity.setRsi(r.getRsi());
        entity.setMacd(r.getMacd());
        entity.setMacdSignal(r.getMacdSignal());
        entity.setMacdHistogram(r.getMacdHistogram());
        entity.setBbUpper(r.getBbUpper());
        entity.setBbLower(r.getBbLower());
        entity.setBbPercentB(r.getBbPercentB());
        entity.setVolumeRatio(r.getVolumeRatio());
        entity.setObvTrend(r.getObvTrend());
        entity.setTechnicalScore(r.getTechnicalScore());
        entity.setRiseProbability(r.getRiseProbability());
        entity.setProbabilitySource(r.getProbabilitySource());
        entity.setProbabilityHorizonDays(r.getProbabilityHorizonDays());
        entity.setTechnicalReason(r.getTechnicalReason());

        technicalScoreRepository.save(entity);
    }

    private double analyzeNewsSentiment(Stock stock, LocalDate today) {
        int maxNews = appProperties.getAnalysis().getNewsPerStock();
        List<NewsArticle> articles = newsCollectorService.fetchRecentNews(stock.getTicker(), maxNews);

        if (articles.isEmpty()) return 0.0;

        double sum = 0;
        int count = 0;

        for (NewsArticle article : articles) {
            if (newsSentimentRepository.existsByTickerAndHeadlineAndDate(
                    stock.getTicker(), article.getHeadline(), article.getDate())) {
                continue;
            }

            SentimentResult sentiment = sentimentAnalysisService.analyze(
                    stock.getName(), article.getHeadline());

            NewsSentiment entity = new NewsSentiment();
            entity.setTicker(stock.getTicker());
            entity.setNewsDate(article.getDate());
            entity.setHeadline(article.getHeadline());
            entity.setUrl(article.getUrl());
            entity.setSentimentScore(sentiment.getScore());
            entity.setSentimentLabel(sentiment.getLabel());
            entity.setSummary(sentiment.getSummary());
            entity.setAnalyzedAt(LocalDateTime.now());
            newsSentimentRepository.save(entity);

            sum += sentiment.getScore();
            count++;
        }

        if (count == 0) {
            int lookback = appProperties.getAnalysis().getNewsLookbackDays();
            List<NewsSentiment> recent = newsSentimentRepository
                    .findByTickerSince(stock.getTicker(), today.minusDays(lookback));
            if (recent.isEmpty()) return 0.0;
            return recent.stream().mapToDouble(NewsSentiment::getSentimentScore).average().orElse(0.0);
        }

        return sum / count;
    }

    private double sentimentToScale100(double sentiment) {
        return (sentiment + 1.0) / 2.0 * 100.0;
    }

    private String toRecommendation(double score) {
        if (score >= 75) return "STRONG_BUY";
        if (score >= 60) return "BUY";
        if (score >= 40) return "HOLD";
        if (score >= 25) return "SELL";
        return "STRONG_SELL";
    }

    private String buildReason(TechnicalIndicatorResult tech, double avgSentiment) {
        String sentimentDesc;
        if (avgSentiment > 0.3)       sentimentDesc = "최근 뉴스 흐름은 긍정적입니다.";
        else if (avgSentiment < -0.3) sentimentDesc = "최근 뉴스 흐름은 부정적입니다.";
        else                          sentimentDesc = "최근 뉴스 흐름은 중립적입니다.";
        return tech.getTechnicalReason() + " " + sentimentDesc;
    }

    private void saveRecommendation(Stock stock, LocalDate today, double techScore,
                                    double sentimentScore, double finalScore,
                                    double riseProbability, String probabilitySource,
                                    Integer probabilityHorizonDays,
                                    String recommendation, String reason) {
        Recommendation entity = recommendationRepository
                .findHistoryByTicker(stock.getTicker())
                .stream()
                .filter(r -> r.getRecDate().equals(today))
                .findFirst()
                .orElse(new Recommendation());

        entity.setTicker(stock.getTicker());
        entity.setRecDate(today);
        entity.setName(stock.getName());
        entity.setMarket(stock.getMarket());
        entity.setTechnicalScore(round2(techScore));
        entity.setSentimentScore(round2(sentimentScore));
        entity.setFinalScore(round2(finalScore));
        entity.setRiseProbability(round2(riseProbability));
        entity.setProbabilitySource(probabilitySource);
        entity.setProbabilityHorizonDays(probabilityHorizonDays);
        entity.setRecommendation(recommendation);
        entity.setReason(reason);

        recommendationRepository.save(entity);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
