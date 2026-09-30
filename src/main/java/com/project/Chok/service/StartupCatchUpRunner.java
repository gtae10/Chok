package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.repository.PriceHistoryRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 사용자가 Spring Boot 앱을 상시 구동하지 않고 필요할 때만 켜는 패턴이라, 평일 18:30
 * cron(AnalysisScheduler)이 그 시각에 앱이 꺼져있으면 그날은 자동 수집/분석이 통째로
 * 스킵된다. 앱을 실제로 켰을 때(=시작 시점)라도 오늘자 데이터가 비어있으면 따라잡도록
 * 보완한다. 수집은 매번 최근 1년치를 통째로 다시 받아오는 방식(collect.py)이라, 며칠이
 * 밀려있어도 한 번 실행하면 공백이 그대로 메워진다.
 */
@Component
public class StartupCatchUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCatchUpRunner.class);

    // 종목 수 대비 이 비율 미만이면 "오늘자 데이터가 사실상 없다"고 간주
    private static final double COVERAGE_THRESHOLD = 0.8;

    private final AppProperties appProperties;
    private final AnalysisStatus analysisStatus;
    private final DataCollectionService dataCollectionService;
    private final RecommendationService recommendationService;
    private final PriceHistoryRepository priceHistoryRepository;
    private final RecommendationRepository recommendationRepository;
    private final StockRepository stockRepository;

    public StartupCatchUpRunner(AppProperties appProperties,
                                 AnalysisStatus analysisStatus,
                                 DataCollectionService dataCollectionService,
                                 RecommendationService recommendationService,
                                 PriceHistoryRepository priceHistoryRepository,
                                 RecommendationRepository recommendationRepository,
                                 StockRepository stockRepository) {
        this.appProperties = appProperties;
        this.analysisStatus = analysisStatus;
        this.dataCollectionService = dataCollectionService;
        this.recommendationService = recommendationService;
        this.priceHistoryRepository = priceHistoryRepository;
        this.recommendationRepository = recommendationRepository;
        this.stockRepository = stockRepository;
    }

    // collect.py는 16:00 KST 전에는 오늘 일봉을 저장하지 않는다(확정 종가만, confirmed_until)
    private static final LocalTime MARKET_CLOSE_CONFIRMED = LocalTime.of(16, 0);

    /**
     * 평일이고, 오늘자 추천(또는 마감 뒤라면 오늘자 가격)이 전체 종목 대비 {@link #COVERAGE_THRESHOLD} 미만이면 보정 대상.
     * 마감 전엔 오늘 가격이 원래 없으므로 가격 커버리지는 보지 않는다 - 보면 낮에 재시작할 때마다 보정이 다시 돈다.
     */
    public static boolean shouldCatchUp(LocalDate today, boolean afterClose, long totalStocks,
                                        long todayPriceCoverage, long todayRecCoverage) {
        DayOfWeek day = today.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        if (totalStocks == 0) {
            return false;
        }
        boolean priceMissing = afterClose && todayPriceCoverage < totalStocks * COVERAGE_THRESHOLD;
        boolean recMissing = todayRecCoverage < totalStocks * COVERAGE_THRESHOLD;
        return priceMissing || recMissing;
    }

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Scheduler scheduler = appProperties.getScheduler();
        if (!scheduler.isEnabled() || !scheduler.isCatchUpOnStartup()) {
            return;
        }

        LocalDate today = LocalDate.now();
        long totalStocks = stockRepository.countCurrentUniverse();
        long todayPriceCoverage = priceHistoryRepository.countDistinctTickersByTradeDate(today);
        long todayRecCoverage = recommendationRepository.countByRecDate(today);

        boolean afterClose = !LocalTime.now(ZoneId.of("Asia/Seoul")).isBefore(MARKET_CLOSE_CONFIRMED);
        if (!shouldCatchUp(today, afterClose, totalStocks, todayPriceCoverage, todayRecCoverage)) {
            log.info("시작 시 자동 보정: 건너뜀 (날짜={}, 종목수={}, 가격 {}/{}, 분석 {}/{})",
                    today, totalStocks, todayPriceCoverage, totalStocks, todayRecCoverage, totalStocks);
            return;
        }

        if (!analysisStatus.tryStart("startup-catchup")) {
            log.info("시작 시 자동 보정: 이미 다른 분석이 진행 중이라 건너뜀");
            return;
        }

        log.info("시작 시 자동 보정: 오늘자 데이터 부족 감지 (가격 {}/{}, 분석 {}/{}) - 시세 수집+분석 시작",
                todayPriceCoverage, totalStocks, todayRecCoverage, totalStocks);

        Thread worker = new Thread(() -> {
            try {
                dataCollectionService.runCollection();
                log.info("시작 시 자동 보정: 시세 수집 완료, 분석 시작");

                int processed = recommendationService.runFullAnalysis(analysisStatus);
                analysisStatus.markDone(processed);
                log.info("시작 시 자동 보정: 완료 ({}개 종목)", processed);
            } catch (Exception e) {
                log.error("시작 시 자동 보정 중 오류", e);
                analysisStatus.markFailed(e.getMessage());
            }
        }, "startup-catchup-runner");
        worker.setDaemon(true);
        worker.start();
    }
}
