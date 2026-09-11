package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.domain.StockReport;
import com.project.Chok.repository.NewsSentimentRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.repository.StockReportRepository;
import com.project.Chok.service.StockReportService;
import com.project.Chok.service.sentiment.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * "점수가 유의미하게 안 바뀌었으면 캐시 재사용, 바뀌었으면(또는 등급이 바뀌면) 재생성"
 * 비용 절감 로직을 검증한다. LLM 호출 여부 자체를 검증 포인트로 삼는다 - 캐시가 재사용될
 * 때는 provider.chat()이 아예 호출되지 않아야 진짜 비용 절감이다.
 */
@ExtendWith(MockitoExtension.class)
class StockReportServiceTest {

    @Mock private RecommendationRepository recommendationRepository;
    @Mock private NewsSentimentRepository newsSentimentRepository;
    @Mock private StockReportRepository stockReportRepository;
    @Mock private LlmProvider llmProvider;

    private AppProperties appProperties;
    private StockReportService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getSentiment().setProvider("openai");
        AppProperties.Analysis analysis = new AppProperties.Analysis();
        analysis.setNewsLookbackDays(3);
        appProperties.setAnalysis(analysis);

        service = new StockReportService(
                recommendationRepository, newsSentimentRepository, stockReportRepository,
                Map.of("openai", llmProvider), appProperties
        );
    }

    private Recommendation createRecommendation(String ticker, double finalScore, String grade) {
        Recommendation r = new Recommendation();
        r.setTicker(ticker);
        r.setName("테스트종목");
        r.setMarket("KOSPI");
        r.setRecDate(LocalDate.now());
        r.setTechnicalScore(finalScore);
        r.setSentimentScore(0.0);
        r.setFinalScore(finalScore);
        r.setRiseProbability(50.0);
        r.setProbabilitySource("HEURISTIC");
        r.setRecommendation(grade);
        r.setReason("테스트 근거");
        return r;
    }

    @Test
    @DisplayName("분석 이력이 없으면 LLM 호출 없이 안내 메시지를 반환한다")
    void no_history_returns_placeholder_without_calling_llm() {
        when(recommendationRepository.findHistoryByTicker(eq("005930"), any())).thenReturn(List.of());

        StockReportService.ReportResult result = service.getOrGenerateReport("005930");

        assertThat(result.reportText()).contains("분석 데이터가 없어");
        assertThat(result.cached()).isFalse();
        verifyNoInteractions(llmProvider);
    }

    @Test
    @DisplayName("직전 생성 대비 점수/등급이 그대로면 캐시를 재사용하고 LLM을 호출하지 않는다")
    void reuses_cache_when_score_unchanged() {
        Recommendation latest = createRecommendation("005930", 70.0, "BUY");
        when(recommendationRepository.findHistoryByTicker(eq("005930"), any())).thenReturn(List.of(latest));

        StockReport cached = new StockReport();
        cached.setTicker("005930");
        cached.setReportText("캐시된 리포트 문장입니다.");
        cached.setGeneratedAt(LocalDateTime.now().minusDays(1));
        cached.setBasedOnDate(LocalDate.now().minusDays(1));
        cached.setBasedOnFinalScore(70.0);
        cached.setBasedOnRecommendation("BUY");
        when(stockReportRepository.findById("005930")).thenReturn(Optional.of(cached));

        StockReportService.ReportResult result = service.getOrGenerateReport("005930");

        assertThat(result.cached()).isTrue();
        assertThat(result.reportText()).isEqualTo("캐시된 리포트 문장입니다.");
        verifyNoInteractions(llmProvider);
        verify(stockReportRepository, never()).save(any());
    }

    @Test
    @DisplayName("점수가 임계값 이상 바뀌면 캐시를 버리고 새로 생성한다")
    void regenerates_when_score_changed_significantly() {
        Recommendation latest = createRecommendation("005930", 80.0, "BUY"); // 캐시 대비 +10점
        when(recommendationRepository.findHistoryByTicker(eq("005930"), any())).thenReturn(List.of(latest));

        StockReport cached = new StockReport();
        cached.setTicker("005930");
        cached.setReportText("옛날 리포트");
        cached.setBasedOnFinalScore(70.0);
        cached.setBasedOnRecommendation("BUY");
        when(stockReportRepository.findById("005930")).thenReturn(Optional.of(cached));
        when(newsSentimentRepository.findByTickerSince(anyString(), any(), any())).thenReturn(List.of());
        when(llmProvider.isConfigured()).thenReturn(true);
        when(llmProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"report\": \"새로 생성된 리포트 문장입니다.\"}");

        StockReportService.ReportResult result = service.getOrGenerateReport("005930");

        assertThat(result.cached()).isFalse();
        assertThat(result.reportText()).isEqualTo("새로 생성된 리포트 문장입니다.");
        verify(llmProvider).chat(anyString(), anyString(), anyInt());

        ArgumentCaptor<StockReport> saved = ArgumentCaptor.forClass(StockReport.class);
        verify(stockReportRepository).save(saved.capture());
        assertThat(saved.getValue().getBasedOnFinalScore()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("점수 변화는 작아도 추천 등급 자체가 바뀌면 재생성한다")
    void regenerates_when_grade_changed_even_with_small_score_delta() {
        Recommendation latest = createRecommendation("005930", 56.0, "HOLD"); // 캐시 대비 +1점뿐
        when(recommendationRepository.findHistoryByTicker(eq("005930"), any())).thenReturn(List.of(latest));

        StockReport cached = new StockReport();
        cached.setTicker("005930");
        cached.setReportText("옛날 리포트(BUY 시절)");
        cached.setBasedOnFinalScore(55.0);
        cached.setBasedOnRecommendation("BUY"); // 등급이 BUY -> HOLD로 경계를 넘음
        when(stockReportRepository.findById("005930")).thenReturn(Optional.of(cached));
        when(newsSentimentRepository.findByTickerSince(anyString(), any(), any())).thenReturn(List.of());
        when(llmProvider.isConfigured()).thenReturn(true);
        when(llmProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"report\": \"신호가 혼재되어 판단을 보류합니다.\"}");

        StockReportService.ReportResult result = service.getOrGenerateReport("005930");

        assertThat(result.cached()).isFalse();
        verify(llmProvider).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("LLM 프로바이더가 설정 안 됐으면 안내 메시지를 반환하고 캐시를 저장하지 않는다")
    void unconfigured_provider_returns_placeholder() {
        Recommendation latest = createRecommendation("005930", 70.0, "BUY");
        when(recommendationRepository.findHistoryByTicker(eq("005930"), any())).thenReturn(List.of(latest));
        when(stockReportRepository.findById("005930")).thenReturn(Optional.empty());
        when(llmProvider.isConfigured()).thenReturn(false);

        StockReportService.ReportResult result = service.getOrGenerateReport("005930");

        assertThat(result.reportText()).contains("LLM 프로바이더 미설정");
        verify(llmProvider, never()).chat(anyString(), anyString(), anyInt());
    }
}
