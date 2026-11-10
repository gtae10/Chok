package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.sentiment.LlmProvider;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RunOnceSafetyTest {

    @Test
    void analyzeBatch_splits_large_input_into_chunks_within_token_cap() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.isConfigured()).thenReturn(true);
        String json = "{\"results\": [" + String.join(",", IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "{\"index\": " + i + ", \"score\": 0.5, \"summary\": \"s\"}").toList()) + "]}";
        List<Integer> maxTokens = new ArrayList<>();
        when(provider.chat(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
            maxTokens.add(inv.getArgument(2));
            return json;
        });
        AppProperties props = new AppProperties();
        props.getSentiment().setProvider("openai");
        SentimentAnalysisService service = new SentimentAnalysisService(Map.of("openai", provider), props);

        List<NewsArticle> articles = IntStream.range(0, 45)
                .mapToObj(i -> new NewsArticle("h" + i, "u" + i, LocalDate.now())).toList();
        List<SentimentResult> results = service.analyzeBatch("삼성전자", articles);

        assertThat(results).hasSize(45).noneMatch(SentimentResult::isFallback);
        assertThat(maxTokens).hasSize(3).allMatch(t -> t < 3000); // 20 + 20 + 5, 잘리지 않을 크기
    }

    private static RecommendationService.AnalysisResult result(int processed, int total, boolean snapshotOk,
                                                               int newsFailed, int analyzed, int fallback) {
        return new RecommendationService.AnalysisResult(processed, total, snapshotOk, newsFailed, analyzed, fallback);
    }

    @Test
    void analysis_result_fails_when_nothing_processed_or_too_many_failures_or_snapshot_failed() {
        assertThat(result(100, 100, true, 0, 0, 0).succeeded()).isTrue();
        assertThat(result(90, 100, true, 0, 0, 0).succeeded()).isTrue();
        assertThat(result(89, 100, true, 0, 0, 0).succeeded()).isFalse();
        assertThat(result(0, 0, true, 0, 0, 0).succeeded()).isFalse();
        assertThat(result(100, 100, false, 0, 0, 0).succeeded()).isFalse();
    }

    @Test
    void analysis_result_fails_when_news_fetch_failure_rate_above_10_percent() {
        assertThat(result(100, 100, true, 10, 0, 0).succeeded()).isTrue();
        assertThat(result(100, 100, true, 11, 0, 0).succeeded()).isFalse();
    }

    @Test
    void analysis_result_fails_when_sentiment_fallback_rate_abnormal() {
        assertThat(result(100, 100, true, 0, 100, 49).succeeded()).isTrue();
        assertThat(result(100, 100, true, 0, 100, 50).succeeded()).isFalse(); // 키 만료·크레딧 소진
        assertThat(result(100, 100, true, 0, 5, 5).succeeded()).isTrue();    // 표본 10건 미만은 판정 안 함
    }
}
