package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.*;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.dto.TechnicalIndicatorResult;
import com.project.Chok.repository.*;
import com.project.Chok.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {

    @Mock private StockRepository stockRepository;
    @Mock private PriceHistoryRepository priceHistoryRepository;
    @Mock private TechnicalScoreRepository technicalScoreRepository;
    @Mock private NewsSentimentRepository newsSentimentRepository;
    @Mock private RecommendationRepository recommendationRepository;
    @Mock private TechnicalAnalysisService technicalAnalysisService;
    @Mock private NewsCollectorService newsCollectorService;
    @Mock private SentimentAnalysisService sentimentAnalysisService;
    @Mock private NewsDeduplicationService newsDeduplicationService;
    @Mock private PerformanceTrackingService performanceTrackingService;

    private AppProperties appProperties;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Analysis analysis = new AppProperties.Analysis();
        analysis.setWeightTechnical(0.65);
        analysis.setWeightSentiment(0.35);
        analysis.setNewsPerStock(5);
        analysis.setNewsLookbackDays(3);
        appProperties.setAnalysis(analysis);

        service = new RecommendationService(
                stockRepository, priceHistoryRepository, technicalScoreRepository,
                newsSentimentRepository, recommendationRepository,
                technicalAnalysisService, newsCollectorService,
                sentimentAnalysisService, newsDeduplicationService,
                performanceTrackingService, appProperties
        );
    }

    private Stock createStock(String ticker, String name) {
        Stock stock = new Stock();
        stock.setTicker(ticker);
        stock.setName(name);
        stock.setMarket("KOSPI");
        stock.setMarketCap(1000000000000L);
        return stock;
    }

    private void mockNormalStock(String ticker) {
        when(priceHistoryRepository.findByTickerOrderByTradeDateAsc(ticker))
                .thenReturn(List.of());
        when(technicalAnalysisService.analyze(any()))
                .thenReturn(TechnicalIndicatorResult.insufficient());
        when(newsCollectorService.fetchRecentNews(eq(ticker), anyInt()))
                .thenReturn(List.of());
        when(technicalScoreRepository.findByTickerAndCalcDate(eq(ticker), any()))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findHistoryByTicker(eq(ticker)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("전체 분석 실행 시 처리된 종목 수를 반환한다")
    void runFullAnalysis_returns_processed_count() {
        List<Stock> stocks = List.of(
                createStock("005930", "삼성전자"),
                createStock("000660", "SK하이닉스")
        );
        when(stockRepository.findAllOrderByMarketCapDesc()).thenReturn(stocks);
        mockNormalStock("005930");
        mockNormalStock("000660");

        int result = service.runFullAnalysis();

        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("긍정 뉴스 + 상승 기술점수 시 BUY 이상 추천이 나와야 한다")
    void positive_news_and_high_tech_score_gives_buy_recommendation() {
        Stock stock = createStock("005930", "삼성전자");

        when(priceHistoryRepository.findByTickerOrderByTradeDateAsc(anyString()))
                .thenReturn(List.of());
        when(technicalAnalysisService.analyze(any()))
                .thenReturn(new TechnicalIndicatorResult(
                        null, null, null, null, null, null, null,
                        null, null, null, 1.0, "RISING",
                        80.0, 70.0, "HEURISTIC", null,
                        null, null, null, null, null, null, "상승추세"
                ));
        when(newsCollectorService.fetchRecentNews(anyString(), anyInt()))
                .thenReturn(List.of(new NewsArticle("호재 뉴스", "http://test.com", LocalDate.now())));
        when(newsSentimentRepository.existsByTickerAndHeadlineAndDate(anyString(), anyString(), any()))
                .thenReturn(false);
        when(newsDeduplicationService.filterSimilarDuplicates(anyString(), anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(sentimentAnalysisService.analyzeBatch(anyString(), anyList()))
                .thenReturn(List.of(new SentimentResult(0.8, "POSITIVE", "호재")));
        when(technicalScoreRepository.findByTickerAndCalcDate(anyString(), any()))
                .thenReturn(Optional.empty());
        when(recommendationRepository.findHistoryByTicker(anyString()))
                .thenReturn(List.of());

        service.analyzeStock(stock, LocalDate.now());

        verify(recommendationRepository).save(argThat(rec ->
                rec.getRecommendation().equals("STRONG_BUY") ||
                        rec.getRecommendation().equals("BUY")
        ));
    }

    @Test
    @DisplayName("종목 분석 실패 시 다른 종목 분석은 계속된다")
    void one_failure_does_not_stop_other_stocks() {
        List<Stock> stocks = List.of(
                createStock("005930", "삼성전자"),
                createStock("000660", "SK하이닉스")
        );
        when(stockRepository.findAllOrderByMarketCapDesc()).thenReturn(stocks);

        // 첫 번째 종목에서 예외 발생
        when(priceHistoryRepository.findByTickerOrderByTradeDateAsc("005930"))
                .thenThrow(new RuntimeException("DB 오류"));

        // 두 번째 종목은 정상
        mockNormalStock("000660");

        int result = service.runFullAnalysis();

        // 첫 번째는 실패, 두 번째는 성공 → 1개 처리
        assertThat(result).isEqualTo(1);
    }

    /**
     * weightTechnical=1.0, weightSentiment=0.0으로 맞춰서 techScore가 그대로 finalScore가
     * 되게 한 뒤, 저장된 Recommendation을 반환한다. 등급/뉘앙스 경계값 테스트 전용.
     */
    private Recommendation analyzeWithScore(double techScore) {
        appProperties.getAnalysis().setWeightTechnical(1.0);
        appProperties.getAnalysis().setWeightSentiment(0.0);

        Stock stock = createStock("005930", "삼성전자");
        when(priceHistoryRepository.findByTickerOrderByTradeDateAsc(anyString())).thenReturn(List.of());
        when(technicalAnalysisService.analyze(any())).thenReturn(new TechnicalIndicatorResult(
                null, null, null, null, null, null, null,
                null, null, null, 1.0, "RISING",
                techScore, 50.0, "HEURISTIC", null,
                null, null, null, null, null, null, "테스트"
        ));
        when(newsCollectorService.fetchRecentNews(anyString(), anyInt())).thenReturn(List.of());
        when(technicalScoreRepository.findByTickerAndCalcDate(anyString(), any())).thenReturn(Optional.empty());
        when(recommendationRepository.findHistoryByTicker(anyString())).thenReturn(List.of());

        service.analyzeStock(stock, LocalDate.now());

        ArgumentCaptor<Recommendation> captor = ArgumentCaptor.forClass(Recommendation.class);
        verify(recommendationRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue(); // 마지막으로 캡처된 값 (이 테스트 메서드 안에서 여러 번 호출될 수 있음)
    }

    @Test
    @DisplayName("75점 이상이면 STRONG_BUY, 그 미만이면 BUY")
    void strong_buy_boundary() {
        assertThat(analyzeWithScore(75.0).getRecommendation()).isEqualTo("STRONG_BUY");
        assertThat(analyzeWithScore(74.9).getRecommendation()).isEqualTo("BUY");
    }

    @Test
    @DisplayName("55점 이상이면 BUY, 그 미만이면 HOLD")
    void buy_boundary() {
        assertThat(analyzeWithScore(55.0).getRecommendation()).isEqualTo("BUY");
        assertThat(analyzeWithScore(54.9).getRecommendation()).isEqualTo("HOLD");
    }

    @Test
    @DisplayName("45점 이상이면 HOLD, 그 미만이면 SELL")
    void hold_boundary() {
        assertThat(analyzeWithScore(45.0).getRecommendation()).isEqualTo("HOLD");
        assertThat(analyzeWithScore(44.9).getRecommendation()).isEqualTo("SELL");
    }

    @Test
    @DisplayName("25점 이상이면 SELL, 그 미만이면 STRONG_SELL")
    void sell_boundary() {
        assertThat(analyzeWithScore(25.0).getRecommendation()).isEqualTo("SELL");
        assertThat(analyzeWithScore(24.9).getRecommendation()).isEqualTo("STRONG_SELL");
    }

    @Test
    @DisplayName("HOLD 중 50점보다 3점 높으면 SLIGHTLY_POSITIVE")
    void hold_nuance_slightly_positive() {
        Recommendation rec = analyzeWithScore(53.0);
        assertThat(rec.getRecommendation()).isEqualTo("HOLD");
        assertThat(rec.getRecommendationNuance()).isEqualTo("SLIGHTLY_POSITIVE");
    }

    @Test
    @DisplayName("HOLD 중 50점보다 3점 낮으면 SLIGHTLY_NEGATIVE")
    void hold_nuance_slightly_negative() {
        Recommendation rec = analyzeWithScore(47.0);
        assertThat(rec.getRecommendation()).isEqualTo("HOLD");
        assertThat(rec.getRecommendationNuance()).isEqualTo("SLIGHTLY_NEGATIVE");
    }

    @Test
    @DisplayName("HOLD 중 50점에 ±2점 이내면 UNCERTAIN (판단 보류)")
    void hold_nuance_uncertain() {
        assertThat(analyzeWithScore(50.0).getRecommendationNuance()).isEqualTo("UNCERTAIN");
        assertThat(analyzeWithScore(52.0).getRecommendationNuance()).isEqualTo("UNCERTAIN");
        assertThat(analyzeWithScore(48.0).getRecommendationNuance()).isEqualTo("UNCERTAIN");
    }

    @Test
    @DisplayName("가격 데이터가 2일 이상 오래되면 AnalysisStatus에 경고가 남는다")
    void stale_price_data_sets_warning() {
        when(stockRepository.findAllOrderByMarketCapDesc()).thenReturn(List.of());
        when(priceHistoryRepository.findLatestTradeDateAcrossAll())
                .thenReturn(LocalDate.now().minusDays(10));

        AnalysisStatus status = new AnalysisStatus();
        status.tryStart("manual");
        service.runFullAnalysis(status);

        assertThat(status.getPriceDataWarning()).isNotNull();
        assertThat(status.getPriceDataWarning()).contains("시세 수집");
    }

    @Test
    @DisplayName("가격 데이터가 최신이면 경고가 없다")
    void fresh_price_data_sets_no_warning() {
        when(stockRepository.findAllOrderByMarketCapDesc()).thenReturn(List.of());
        when(priceHistoryRepository.findLatestTradeDateAcrossAll())
                .thenReturn(LocalDate.now());

        AnalysisStatus status = new AnalysisStatus();
        status.tryStart("manual");
        service.runFullAnalysis(status);

        assertThat(status.getPriceDataWarning()).isNull();
    }

    @Test
    @DisplayName("UNCERTAIN 경계 바로 밖(52.1)은 SLIGHTLY_POSITIVE로 넘어간다")
    void hold_nuance_just_outside_uncertain_band() {
        assertThat(analyzeWithScore(52.1).getRecommendationNuance()).isEqualTo("SLIGHTLY_POSITIVE");
    }

    @Test
    @DisplayName("HOLD가 아닌 등급은 뉘앙스가 null이다")
    void nuance_is_null_when_not_hold() {
        assertThat(analyzeWithScore(60.0).getRecommendationNuance()).isNull();
        assertThat(analyzeWithScore(30.0).getRecommendationNuance()).isNull();
    }
}