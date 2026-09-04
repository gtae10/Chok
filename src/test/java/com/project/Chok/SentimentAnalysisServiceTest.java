package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.SentimentAnalysisService;
import com.project.Chok.service.sentiment.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * SentimentAnalysisService 자체의 로직(프로바이더 선택, 결과 JSON 파싱, 예외 시 중립 폴백,
 * LLM 호출 동시성 상한)을 검증한다. 프로바이더별 HTTP 요청/응답 형식 검증은
 * {@link OpenAiProviderTest}, {@link AnthropicProviderTest}에서 별도로 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class SentimentAnalysisServiceTest {

    @Mock
    private LlmProvider openAiProvider;

    @Mock
    private LlmProvider anthropicProvider;

    private AppProperties appProperties;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getSentiment().setProvider("openai");
        appProperties.getSentiment().setMaxConcurrentCalls(3);
    }

    private SentimentAnalysisService service() {
        return new SentimentAnalysisService(
                Map.of("openai", openAiProvider, "anthropic", anthropicProvider),
                appProperties);
    }

    @Test
    @DisplayName("설정된 provider가 존재하지 않으면 중립 반환")
    void unknown_provider_returns_neutral() {
        appProperties.getSentiment().setProvider("does-not-exist");

        SentimentResult result = service().analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        verifyNoInteractions(openAiProvider, anthropicProvider);
    }

    @Test
    @DisplayName("provider가 설정되어 있지 않으면(API 키 미설정) 중립 반환하고 호출하지 않는다")
    void unconfigured_provider_returns_neutral_without_calling() {
        when(openAiProvider.isConfigured()).thenReturn(false);

        SentimentResult result = service().analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        assertThat(result.getScore()).isEqualTo(0.0);
        verify(openAiProvider, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("chok.sentiment.provider 설정값으로 프로바이더를 선택한다")
    void selects_provider_by_config() {
        appProperties.getSentiment().setProvider("anthropic");
        when(anthropicProvider.isConfigured()).thenReturn(true);
        when(anthropicProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": 0.5, \"label\": \"POSITIVE\", \"summary\": \"긍정적\"}");

        service().analyze("삼성전자", "삼성전자 실적 발표");

        verify(anthropicProvider).chat(anyString(), anyString(), anyInt());
        verifyNoInteractions(openAiProvider);
    }

    @Test
    @DisplayName("긍정 뉴스 응답을 올바르게 파싱한다")
    void parses_positive_sentiment_correctly() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": 0.8, \"label\": \"POSITIVE\", \"summary\": \"실적 개선 호재\"}");

        SentimentResult result = service().analyze("삼성전자", "삼성전자 역대 최대 실적 달성");

        assertThat(result.getLabel()).isEqualTo("POSITIVE");
        assertThat(result.getScore()).isEqualTo(0.8);
        assertThat(result.getSummary()).isEqualTo("실적 개선 호재");
    }

    @Test
    @DisplayName("부정 뉴스 응답을 올바르게 파싱한다")
    void parses_negative_sentiment_correctly() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": -0.7, \"label\": \"NEGATIVE\", \"summary\": \"소송 리스크 악재\"}");

        SentimentResult result = service().analyze("카카오", "카카오 공정위 제재 리스크");

        assertThat(result.getLabel()).isEqualTo("NEGATIVE");
        assertThat(result.getScore()).isEqualTo(-0.7);
        assertThat(result.getSummary()).isEqualTo("소송 리스크 악재");
    }

    @Test
    @DisplayName("마크다운 코드블록으로 감싸진 응답도 파싱한다")
    void strips_markdown_code_fence_before_parsing() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("```json\n{\"score\": 0.3, \"label\": \"POSITIVE\", \"summary\": \"소폭 호재\"}\n```");

        SentimentResult result = service().analyze("삼성전자", "삼성전자 소폭 실적 개선");

        assertThat(result.getLabel()).isEqualTo("POSITIVE");
        assertThat(result.getScore()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("provider.chat() 호출 중 예외 발생 시 중립 반환")
    void provider_exception_returns_neutral() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));

        SentimentResult result = service().analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        assertThat(result.getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("응답이 올바른 JSON이 아니면 중립 반환")
    void malformed_response_returns_neutral() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("이건 JSON이 아님");

        SentimentResult result = service().analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        assertThat(result.getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("점수는 항상 -1.0~1.0 범위로 clamp된다")
    void score_is_always_within_range() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": 5.0, \"label\": \"POSITIVE\", \"summary\": \"과도한 점수\"}");

        SentimentResult result = service().analyze("테스트", "테스트 뉴스");

        assertThat(result.getScore())
                .isGreaterThanOrEqualTo(-1.0)
                .isLessThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("chok.sentiment.max-concurrent-calls 상한을 넘는 동시 LLM 호출이 없다")
    void limits_concurrent_provider_calls() throws InterruptedException {
        int maxConcurrent = 2;
        int totalCalls = 8;
        appProperties.getSentiment().setMaxConcurrentCalls(maxConcurrent);

        AtomicInteger inFlight = new AtomicInteger(0);
        AtomicInteger maxObserved = new AtomicInteger(0);

        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            int current = inFlight.incrementAndGet();
            maxObserved.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(50);
            } finally {
                inFlight.decrementAndGet();
            }
            return "{\"score\": 0.0, \"label\": \"NEUTRAL\", \"summary\": \"ok\"}";
        });

        SentimentAnalysisService service = service();
        ExecutorService pool = Executors.newFixedThreadPool(totalCalls);
        CountDownLatch done = new CountDownLatch(totalCalls);
        try {
            for (int i = 0; i < totalCalls; i++) {
                pool.submit(() -> {
                    try {
                        service.analyze("종목", "헤드라인");
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(maxObserved.get()).isLessThanOrEqualTo(maxConcurrent);
    }

    private static NewsArticle article(String headline) {
        return new NewsArticle(headline, "http://test.com", LocalDate.now());
    }

    @Test
    @DisplayName("빈 뉴스 목록이면 배치 호출 자체를 생략한다")
    void analyzeBatch_skips_call_when_articles_empty() {
        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of());

        assertThat(results).isEmpty();
        verifyNoInteractions(openAiProvider);
    }

    @Test
    @DisplayName("배치 응답을 헤드라인 개수만큼, 순서대로 파싱한다")
    void analyzeBatch_parses_all_headlines_in_order() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt())).thenReturn("""
                {"results": [
                    {"index": 1, "score": 0.8, "label": "POSITIVE", "summary": "실적 서프라이즈"},
                    {"index": 2, "score": -0.5, "label": "NEGATIVE", "summary": "규제 리스크"},
                    {"index": 3, "score": 0.1, "label": "NEUTRAL", "summary": "PR성 뉴스"}
                ]}
                """);

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2"), article("헤드라인3")));

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getScore()).isEqualTo(0.8);
        assertThat(results.get(1).getScore()).isEqualTo(-0.5);
        assertThat(results.get(2).getScore()).isEqualTo(0.1);
        verify(openAiProvider, times(1)).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("배치 응답의 index 순서가 뒤섞여도 원래 헤드라인 위치에 정확히 매칭한다")
    void analyzeBatch_matches_by_index_even_when_order_shuffled() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt())).thenReturn("""
                {"results": [
                    {"index": 3, "score": 0.3, "label": "POSITIVE", "summary": "세번째"},
                    {"index": 1, "score": -0.9, "label": "NEGATIVE", "summary": "첫번째"},
                    {"index": 2, "score": 0.0, "label": "NEUTRAL", "summary": "두번째"}
                ]}
                """);

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2"), article("헤드라인3")));

        assertThat(results.get(0).getScore()).isEqualTo(-0.9);
        assertThat(results.get(1).getScore()).isEqualTo(0.0);
        assertThat(results.get(2).getScore()).isEqualTo(0.3);
    }

    @Test
    @DisplayName("배치 응답에 일부 index가 누락되면 해당 헤드라인만 중립 처리한다")
    void analyzeBatch_defaults_missing_index_to_neutral() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt())).thenReturn("""
                {"results": [
                    {"index": 1, "score": 0.6, "label": "POSITIVE", "summary": "대형 계약"}
                ]}
                """);

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2")));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getScore()).isEqualTo(0.6);
        assertThat(results.get(1).getLabel()).isEqualTo("NEUTRAL");
        assertThat(results.get(1).getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("배치 응답이 JSON 배열이 아니면 전체를 중립으로 폴백한다")
    void analyzeBatch_falls_back_to_neutral_when_response_not_array() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": 0.5, \"label\": \"POSITIVE\", \"summary\": \"단건 객체로 잘못 응답\"}");

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2")));

        assertThat(results).hasSize(2);
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getLabel()).isEqualTo("NEUTRAL");
            assertThat(r.getScore()).isEqualTo(0.0);
        });
    }

    @Test
    @DisplayName("배치 호출 중 예외 발생 시 전체를 중립으로 폴백한다")
    void analyzeBatch_falls_back_to_neutral_on_provider_exception() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Connection refused"));

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2"), article("헤드라인3")));

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(r -> assertThat(r.getLabel()).isEqualTo("NEUTRAL"));
    }

    @Test
    @DisplayName("provider 미설정 시 배치도 호출 없이 중립 리스트를 반환한다")
    void analyzeBatch_returns_neutral_list_when_unconfigured() {
        when(openAiProvider.isConfigured()).thenReturn(false);

        List<SentimentResult> results = service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2")));

        assertThat(results).hasSize(2);
        verify(openAiProvider, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("단건 analyze()는 maxTokens를 헤드라인 1건 기준으로 계산해서 넘긴다")
    void analyze_passes_max_tokens_for_single_headline() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"score\": 0.0, \"label\": \"NEUTRAL\", \"summary\": \"ok\"}");

        service().analyze("삼성전자", "헤드라인");

        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        verify(openAiProvider).chat(anyString(), anyString(), maxTokens.capture());
        assertThat(maxTokens.getValue()).isEqualTo(320); // 200 + 1*120
    }

    @Test
    @DisplayName("analyzeBatch()는 maxTokens를 헤드라인 개수에 비례해서 계산해서 넘긴다")
    void analyzeBatch_passes_max_tokens_proportional_to_batch_size() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"results\": []}");

        service().analyzeBatch("삼성전자", List.of(
                article("헤드라인1"), article("헤드라인2"), article("헤드라인3"),
                article("헤드라인4"), article("헤드라인5")));

        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        verify(openAiProvider).chat(anyString(), anyString(), maxTokens.capture());
        assertThat(maxTokens.getValue()).isEqualTo(800); // 200 + 5*120
    }

    @Test
    @DisplayName("maxTokens는 하한(300) 아래로 내려가지 않는다")
    void maxTokens_never_goes_below_floor() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"results\": [{\"index\":1,\"score\":0.0,\"label\":\"NEUTRAL\",\"summary\":\"ok\"}]}");

        // 200 + 1*120 = 320 > 300이므로 하한에 걸리는 경우는 이론상 없지만,
        // 공식이 항상 최소 300 이상을 보장하는지 회귀적으로 확인한다.
        service().analyzeBatch("삼성전자", List.of(article("헤드라인1")));

        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        verify(openAiProvider).chat(anyString(), anyString(), maxTokens.capture());
        assertThat(maxTokens.getValue()).isGreaterThanOrEqualTo(300);
    }

    @Test
    @DisplayName("maxTokens는 상한(3000)을 넘지 않는다")
    void maxTokens_capped_at_ceiling() {
        when(openAiProvider.isConfigured()).thenReturn(true);
        when(openAiProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"results\": []}");

        List<NewsArticle> manyArticles = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            manyArticles.add(article("헤드라인" + i));
        }
        service().analyzeBatch("삼성전자", manyArticles);

        ArgumentCaptor<Integer> maxTokens = ArgumentCaptor.forClass(Integer.class);
        verify(openAiProvider).chat(anyString(), anyString(), maxTokens.capture());
        assertThat(maxTokens.getValue()).isEqualTo(3000);
    }
}
