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
import java.time.temporal.ChronoUnit;
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
    private final NewsDeduplicationService newsDeduplicationService;
    private final PerformanceTrackingService performanceTrackingService;

    private final AppProperties appProperties;

    public RecommendationService(StockRepository stockRepository,
                                 PriceHistoryRepository priceHistoryRepository,
                                 TechnicalScoreRepository technicalScoreRepository,
                                 NewsSentimentRepository newsSentimentRepository,
                                 RecommendationRepository recommendationRepository,
                                 TechnicalAnalysisService technicalAnalysisService,
                                 NewsCollectorService newsCollectorService,
                                 SentimentAnalysisService sentimentAnalysisService,
                                 NewsDeduplicationService newsDeduplicationService,
                                 PerformanceTrackingService performanceTrackingService,
                                 AppProperties appProperties) {
        this.stockRepository = stockRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.technicalScoreRepository = technicalScoreRepository;
        this.newsSentimentRepository = newsSentimentRepository;
        this.recommendationRepository = recommendationRepository;
        this.technicalAnalysisService = technicalAnalysisService;
        this.newsCollectorService = newsCollectorService;
        this.sentimentAnalysisService = sentimentAnalysisService;
        this.newsDeduplicationService = newsDeduplicationService;
        this.performanceTrackingService = performanceTrackingService;
        this.appProperties = appProperties;
    }

    public int runFullAnalysis() {
        return runFullAnalysis(null);
    }

    /**
     * 종목별 분석을 병렬로 실행한다. 뉴스 감성분석이 종목당 블로킹 API 호출 1번(뉴스 여러 건을
     * 배치로 묶어서 요청)을 포함하므로, 순차 실행 시 종목 수만큼 직렬로 대기해야 했음.
     * 스레드풀로 동시에 여러 종목을 처리해 전체 소요 시간을 줄인다.
     * (동시성 상한은 chok.analysis.parallelism 로 조절 — LLM API로 나가는 동시 요청 수 자체는
     * chok.sentiment.max-concurrent-calls로 별도 제한됨)
     */
    // 이 정도 오래되면(달력일 기준) "며칠 전 가격으로 분석 중"이라는 경고를 띄운다.
    private static final int STALE_PRICE_WARNING_DAYS = 2;

    public int runFullAnalysis(AnalysisStatus status) {
        List<Stock> stocks = stockRepository.findAllOrderByMarketCapDesc();
        LocalDate today = LocalDate.now();
        checkStalePriceData(today, status);
        int total = stocks.size();
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger completed = new AtomicInteger(0);

        int poolSize = Math.max(1, appProperties.getAnalysis().getParallelism());
        log.info("전체 분석 시작: {}개 종목, 병렬도={}", total, poolSize);
        if (status != null) status.updateProgress(0, total);

        AtomicInteger sentimentAnalyzed = new AtomicInteger(0);
        AtomicInteger sentimentFallback = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<Void>> futures = stocks.stream()
                    .map(stock -> CompletableFuture.runAsync(() -> {
                        try {
                            analyzeStock(stock, today, sentimentAnalyzed, sentimentFallback);
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
        warnIfSentimentFailureRateAbnormal(sentimentAnalyzed.get(), sentimentFallback.get());

        try {
            performanceTrackingService.saveSnapshot(today);
        } catch (Exception e) {
            log.error("성과 스냅샷 저장 실패 (date={}): {}", today, e.getMessage());
        }

        return processed.get();
    }

    /** 오래된 가격 데이터로 분석 중이면 AnalysisStatus에 경고를 남긴다 (분석 자체는 막지 않음). */
    private void checkStalePriceData(LocalDate today, AnalysisStatus status) {
        LocalDate latestPriceDate = priceHistoryRepository.findLatestTradeDateAcrossAll();
        if (latestPriceDate == null) return;

        LocalDate lastTradingDay = lastTradingDayOnOrBefore(today);
        long staleDays = ChronoUnit.DAYS.between(latestPriceDate, lastTradingDay);
        if (staleDays < STALE_PRICE_WARNING_DAYS) return;

        String warning = String.format(
                "가격 데이터가 %d일 전(%s) 것입니다. 먼저 시세 수집을 하는 것을 권장합니다.",
                staleDays, latestPriceDate);
        log.warn(warning);
        if (status != null) status.setPriceDataWarning(warning);
    }

    private LocalDate lastTradingDayOnOrBefore(LocalDate date) {
        return switch (date.getDayOfWeek()) {
            case SATURDAY -> date.minusDays(1);
            case SUNDAY -> date.minusDays(2);
            default -> date;
        };
    }

    @Transactional
    public void analyzeStock(Stock stock, LocalDate today) {
        analyzeStock(stock, today, new AtomicInteger(), new AtomicInteger());
    }

    @Transactional
    public void analyzeStock(Stock stock, LocalDate today,
                              AtomicInteger sentimentAnalyzed, AtomicInteger sentimentFallback) {
        String ticker = stock.getTicker();

        List<PriceHistory> prices = priceHistoryRepository.findByTickerOrderByTradeDateAsc(ticker);
        TechnicalIndicatorResult techResult = technicalAnalysisService.analyze(prices);
        saveTechnicalScore(ticker, today, techResult);

        SentimentOutcome sentimentOutcome = analyzeNewsSentiment(stock, today, sentimentAnalyzed, sentimentFallback);
        double avgSentiment = sentimentOutcome.avgScore();

        AppProperties.Analysis analysis = appProperties.getAnalysis();
        double finalScore = (techResult.getTechnicalScore() * analysis.getWeightTechnical())
                + (sentimentToScale100(avgSentiment) * analysis.getWeightSentiment());
        finalScore = Math.max(0, Math.min(100, finalScore));

        String recommendation = toRecommendation(finalScore);
        String nuance = toRecommendationNuance(recommendation, finalScore);
        String reason = buildReason(techResult, avgSentiment);

        saveRecommendation(stock, today, techResult.getTechnicalScore(),
                avgSentiment, sentimentOutcome.fallbackUsed(), finalScore, techResult.getRiseProbability(),
                techResult.getProbabilitySource(), techResult.getProbabilityHorizonDays(),
                techResult.getNotableHorizonDays(), techResult.getNotableHorizonProbability(),
                techResult.getNotableHorizonApproxDate(),
                recommendation, nuance, reason);
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
        entity.setNotableHorizonDays(r.getNotableHorizonDays());
        entity.setNotableHorizonProbability(r.getNotableHorizonProbability());
        entity.setNotableHorizonApproxDate(r.getNotableHorizonApproxDate() == null ? null : LocalDate.parse(r.getNotableHorizonApproxDate()));
        entity.setTechnicalReason(r.getTechnicalReason());

        technicalScoreRepository.save(entity);
    }

    /** analyzeNewsSentiment의 결과 - 평균 점수와, 그 중 하나 이상이 오류 폴백이었는지 여부. */
    private record SentimentOutcome(double avgScore, boolean fallbackUsed) {
        static SentimentOutcome of(double avgScore) {
            return new SentimentOutcome(avgScore, false);
        }
    }

    private SentimentOutcome analyzeNewsSentiment(Stock stock, LocalDate today,
                                                   AtomicInteger sentimentAnalyzed, AtomicInteger sentimentFallback) {
        int maxNews = appProperties.getAnalysis().getNewsPerStock();
        List<NewsArticle> articles = newsCollectorService.fetchRecentNews(stock.getTicker(), maxNews);

        if (articles.isEmpty()) return SentimentOutcome.of(0.0);

        List<NewsArticle> toAnalyze = articles.stream()
                .filter(article -> !newsSentimentRepository.existsByTickerAndHeadlineAndDate(
                        stock.getTicker(), article.getHeadline(), article.getDate()))
                .toList();

        // 여러 언론사가 같은 사건을 토씨만 다르게 보도한 헤드라인을 LLM 호출 전에 걸러낸다
        // (완전 일치 dedup만으로는 못 잡음 - 토큰 절약을 위해 배치 구성 이전 단계에서 처리)
        toAnalyze = newsDeduplicationService.filterSimilarDuplicates(stock.getTicker(), toAnalyze);

        double sum = 0;
        int count = 0;
        boolean fallbackUsed = false;

        if (!toAnalyze.isEmpty()) {
            List<SentimentResult> sentiments = sentimentAnalysisService.analyzeBatch(
                    stock.getName(), toAnalyze);

            for (int i = 0; i < toAnalyze.size(); i++) {
                NewsArticle article = toAnalyze.get(i);
                SentimentResult sentiment = sentiments.get(i);

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
                fallbackUsed = fallbackUsed || sentiment.isFallback();

                sentimentAnalyzed.incrementAndGet();
                if (sentiment.isFallback()) sentimentFallback.incrementAndGet();
            }
        }

        if (count == 0) {
            // ponytail: 이 경로(오늘 새 뉴스 없음 -> 최근 lookback 평균 재사용)는 과거에 저장된
            // NewsSentiment가 그 자체로 오류 폴백이었는지는 구분하지 않는다. news_sentiment에도
            // fallback 플래그를 추가해 여기서 걸러내는 게 이상적이지만, 지금 문제가 된 경로(위의
            // 신규 분석 실패)보다 영향이 작아 우선순위를 낮췄다 - 필요해지면 추가.
            int lookback = appProperties.getAnalysis().getNewsLookbackDays();
            List<NewsSentiment> recent = newsSentimentRepository
                    .findByTickerSince(stock.getTicker(), today.minusDays(lookback));
            if (recent.isEmpty()) return SentimentOutcome.of(0.0);
            return SentimentOutcome.of(recent.stream().mapToDouble(NewsSentiment::getSentimentScore).average().orElse(0.0));
        }

        return new SentimentOutcome(sum / count, fallbackUsed);
    }

    /** 감성분석 실패율이 비정상적으로 높으면(대규모 API 장애 가능성) 눈에 띄게 경고한다.
     * 2026-06-27~07-23 API 장애로 638건이 조용히 오류 폴백값으로 저장됐던 사고 이후 추가. */
    private static final double ABNORMAL_FALLBACK_RATE = 0.5;
    private static final int MIN_SAMPLES_FOR_RATE_CHECK = 10;

    private void warnIfSentimentFailureRateAbnormal(int analyzed, int fallback) {
        if (analyzed < MIN_SAMPLES_FOR_RATE_CHECK) return;
        double rate = (double) fallback / analyzed;
        if (rate < ABNORMAL_FALLBACK_RATE) return;
        log.warn("!!! 감성분석 실패율 비정상 - {}건 중 {}건({}%)이 오류 폴백값입니다. "
                        + "LLM API 키/크레딧/레이트리밋을 확인하세요. 이 상태로 쌓인 recommendations는 "
                        + "sentimentDataQuality=FALLBACK으로 표시됩니다.",
                analyzed, fallback, Math.round(rate * 100));
    }

    private double sentimentToScale100(double sentiment) {
        return (sentiment + 1.0) / 2.0 * 100.0;
    }

    // HOLD 구간을 40~60(폭 20)에서 45~55(폭 10)로 좁힘 - 기존엔 실제 DB 데이터의 80%가
    // HOLD로 몰려서 "중립"만 계속 뜨는 문제가 있었음. BUY/SELL은 그만큼 넓어짐(각 15→20).
    private static final double STRONG_BUY_THRESHOLD = 75;
    private static final double BUY_THRESHOLD = 55;
    private static final double HOLD_THRESHOLD = 45;
    private static final double SELL_THRESHOLD = 25;

    // HOLD 안에서 50점 대비 "판단 보류"로 볼 중립 구간의 반폭 (50 ± NUANCE_NEUTRAL_BAND)
    private static final double NUANCE_NEUTRAL_BAND = 2;

    private String toRecommendation(double score) {
        if (score >= STRONG_BUY_THRESHOLD) return "STRONG_BUY";
        if (score >= BUY_THRESHOLD) return "BUY";
        if (score >= HOLD_THRESHOLD) return "HOLD";
        if (score >= SELL_THRESHOLD) return "SELL";
        return "STRONG_SELL";
    }

    /** HOLD로 분류된 경우에만 50점 대비 어느 쪽에 가까운지 세분화, 그 외엔 null. */
    private String toRecommendationNuance(String recommendation, double score) {
        if (!"HOLD".equals(recommendation)) return null;
        double diff = score - 50;
        if (Math.abs(diff) <= NUANCE_NEUTRAL_BAND) return "UNCERTAIN";
        return diff > 0 ? "SLIGHTLY_POSITIVE" : "SLIGHTLY_NEGATIVE";
    }

    private String buildReason(TechnicalIndicatorResult tech, double avgSentiment) {
        String sentimentDesc;
        if (avgSentiment > 0.3)       sentimentDesc = "최근 뉴스 흐름은 긍정적입니다.";
        else if (avgSentiment < -0.3) sentimentDesc = "최근 뉴스 흐름은 부정적입니다.";
        else                          sentimentDesc = "최근 뉴스 흐름은 중립적입니다.";
        return tech.getTechnicalReason() + " " + sentimentDesc;
    }

    private void saveRecommendation(Stock stock, LocalDate today, double techScore,
                                    double sentimentScore, boolean sentimentFallbackUsed, double finalScore,
                                    double riseProbability, String probabilitySource,
                                    Integer probabilityHorizonDays,
                                    Integer notableHorizonDays, Double notableHorizonProbability,
                                    String notableHorizonApproxDate,
                                    String recommendation, String recommendationNuance, String reason) {
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
        entity.setSentimentDataQuality(sentimentFallbackUsed ? "FALLBACK" : "OK");
        entity.setFinalScore(round2(finalScore));
        entity.setRiseProbability(round2(riseProbability));
        entity.setProbabilitySource(probabilitySource);
        entity.setProbabilityHorizonDays(probabilityHorizonDays);
        entity.setNotableHorizonDays(notableHorizonDays);
        entity.setNotableHorizonProbability(notableHorizonProbability);
        entity.setNotableHorizonApproxDate(notableHorizonApproxDate == null ? null : LocalDate.parse(notableHorizonApproxDate));
        entity.setRecommendation(recommendation);
        entity.setRecommendationNuance(recommendationNuance);
        entity.setReason(reason);

        recommendationRepository.save(entity);
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
