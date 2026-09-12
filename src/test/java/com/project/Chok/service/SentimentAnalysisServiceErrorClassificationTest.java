package com.project.Chok.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.sentiment.OpenAiProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SentimentAnalysisService.classifyFailure()가 예외 종류/HTTP 상태코드별로 원인을 제대로
 * 구분하는지 검증한다. 2026-06-27~07-23 API 장애 때 3,365건이 전부 "API 호출 오류"로만
 * 뭉개져서 원인 파악이 늦어졌던 사고 이후 추가된 로직에 대한 회귀 테스트.
 */
class SentimentAnalysisServiceErrorClassificationTest {

    private SentimentAnalysisService newService() {
        return new SentimentAnalysisService(Map.of(), new AppProperties());
    }

    @Test
    @DisplayName("401 -> 인증실패로 분류")
    void classifies_401_as_auth_failure() {
        WebClientResponseException e = WebClientResponseException.create(
                401, "Unauthorized", new HttpHeaders(), "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(newService().classifyFailure(e)).contains("인증실패").contains("401");
    }

    @Test
    @DisplayName("429 + insufficient_quota 응답 본문 -> 크레딧 소진으로 분류")
    void classifies_429_with_quota_body_as_credit_exhausted() {
        String body = "{\"error\":{\"type\":\"insufficient_quota\"}}";
        WebClientResponseException e = WebClientResponseException.create(
                429, "Too Many Requests", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(newService().classifyFailure(e)).contains("크레딧 소진");
    }

    @Test
    @DisplayName("429 (quota 언급 없는 본문) -> 레이트리밋으로 분류")
    void classifies_429_without_quota_body_as_rate_limit() {
        WebClientResponseException e = WebClientResponseException.create(
                429, "Too Many Requests", new HttpHeaders(), "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(newService().classifyFailure(e)).contains("레이트리밋").doesNotContain("크레딧 소진");
    }

    @Test
    @DisplayName("500 -> 프로바이더 서버 오류로 분류")
    void classifies_500_as_server_error() {
        WebClientResponseException e = WebClientResponseException.create(
                500, "Internal Server Error", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        assertThat(newService().classifyFailure(e)).contains("서버 오류").contains("500");
    }

    @Test
    @DisplayName("타임아웃 -> 타임아웃으로 분류")
    void classifies_timeout_as_timeout() {
        WebClientRequestException e = new WebClientRequestException(
                new TimeoutException("timed out"), HttpMethod.POST, URI.create("http://test.local"), new HttpHeaders());
        assertThat(newService().classifyFailure(e)).isEqualTo("타임아웃");
    }

    @Test
    @DisplayName("알 수 없는 예외 -> 예외 클래스명과 함께 분류 (묻히지 않도록)")
    void classifies_unknown_exception_with_class_name() {
        IllegalStateException e = new IllegalStateException("뭔가 이상함");
        assertThat(newService().classifyFailure(e)).contains("IllegalStateException");
    }

    /**
     * 일부러 잘못된 API 키로 실제 OpenAI 엔드포인트를 호출해, 원인(인증실패)이 반환값과
     * 로그 양쪽에 제대로 남는지 확인한다. 401 응답은 완성 토큰이 과금되지 않아 비용은 없음.
     */
    @Test
    @Tag("llm-live")
    @DisplayName("실제 잘못된 키 호출 시 인증실패로 분류되어 로그에 남는지 확인")
    void real_call_with_invalid_key_logs_classified_reason() {
        AppProperties appProperties = new AppProperties();

        AppProperties.Sentiment sentiment = new AppProperties.Sentiment();
        sentiment.setProvider("openai");
        sentiment.setMaxConcurrentCalls(1);
        appProperties.setSentiment(sentiment);

        AppProperties.OpenAi openai = new AppProperties.OpenAi();
        openai.setApiKey("sk-deliberately-invalid-for-error-classification-test");
        openai.setModel("gpt-4o-mini");
        openai.setBaseUrl("https://api.openai.com/v1/chat/completions");
        appProperties.setOpenai(openai);

        WebClient webClient = WebClient.builder().build();
        SentimentAnalysisService service = new SentimentAnalysisService(
                Map.of("openai", new OpenAiProvider(webClient, appProperties)), appProperties);

        ch.qos.logback.classic.Logger logbackLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SentimentAnalysisService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            SentimentResult result = service.analyze("테스트기업", "테스트 헤드라인");

            assertThat(result.isFallback()).isTrue();
            assertThat(result.getSummary()).contains("인증실패");

            boolean logged = appender.list.stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("인증실패"));
            assertThat(logged).withFailMessage("로그에 인증실패 분류가 남아야 함").isTrue();
        } finally {
            logbackLogger.detachAppender(appender);
        }
    }
}
