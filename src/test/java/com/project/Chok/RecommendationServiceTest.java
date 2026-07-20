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
                sentimentAnalysisService, appProperties
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
        when(newsSentimentRepository.findByTickerSince(eq(ticker), any()))
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
                        80.0, 70.0, "HEURISTIC", "상승추세"
                ));
        when(newsCollectorService.fetchRecentNews(anyString(), anyInt()))
                .thenReturn(List.of(new NewsArticle("호재 뉴스", "http://test.com", LocalDate.now())));
        when(newsSentimentRepository.existsByTickerAndHeadlineAndDate(anyString(), anyString(), any()))
                .thenReturn(false);
        when(sentimentAnalysisService.analyze(anyString(), anyString()))
                .thenReturn(new SentimentResult(0.8, "POSITIVE", "호재"));
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
}