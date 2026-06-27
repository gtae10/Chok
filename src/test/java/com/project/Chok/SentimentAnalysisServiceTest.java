package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.SentimentAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SentimentAnalysisServiceTest {

    @Mock
    private WebClient webClient;

    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private WebClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private WebClient.ResponseSpec responseSpec;

    private AppProperties appProperties;
    private SentimentAnalysisService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.Anthropic anthropic = new AppProperties.Anthropic();
        anthropic.setApiKey("test-api-key");
        anthropic.setModel("claude-sonnet-4-6");
        anthropic.setBaseUrl("https://api.anthropic.com/v1/messages");
        appProperties.setAnthropic(anthropic);

        service = new SentimentAnalysisService(webClient, appProperties);
    }

    @SuppressWarnings("unchecked")
    private void mockWebClient(String mockResponse) {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(any(String.class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any(String[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(mockResponse));
    }

    @Test
    @DisplayName("API 키 미설정 시 중립 반환")
    void no_api_key_returns_neutral() {
        AppProperties.Anthropic anthropic = new AppProperties.Anthropic();
        anthropic.setApiKey("");
        appProperties.setAnthropic(anthropic);

        SentimentResult result = service.analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        assertThat(result.getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("긍정 뉴스 응답을 올바르게 파싱한다")
    void parses_positive_sentiment_correctly() {
        mockWebClient("""
                {
                    "content": [
                        {
                            "type": "text",
                            "text": "{\\"score\\": 0.8, \\"label\\": \\"POSITIVE\\", \\"summary\\": \\"실적 개선 호재\\"}"
                        }
                    ]
                }
                """);

        SentimentResult result = service.analyze("삼성전자", "삼성전자 역대 최대 실적 달성");

        assertThat(result.getLabel()).isEqualTo("POSITIVE");
        assertThat(result.getScore()).isEqualTo(0.8);
        assertThat(result.getSummary()).isEqualTo("실적 개선 호재");
    }

    @Test
    @DisplayName("부정 뉴스 응답을 올바르게 파싱한다")
    void parses_negative_sentiment_correctly() {
        mockWebClient("""
                {
                    "content": [
                        {
                            "type": "text",
                            "text": "{\\"score\\": -0.7, \\"label\\": \\"NEGATIVE\\", \\"summary\\": \\"소송 리스크 악재\\"}"
                        }
                    ]
                }
                """);

        SentimentResult result = service.analyze("카카오", "카카오 공정위 제재 리스크");

        assertThat(result.getLabel()).isEqualTo("NEGATIVE");
        assertThat(result.getScore()).isEqualTo(-0.7);
        assertThat(result.getSummary()).isEqualTo("소송 리스크 악재");
    }

    @Test
    @DisplayName("API 호출 중 예외 발생 시 중립 반환")
    void api_exception_returns_neutral() {
        when(webClient.post()).thenThrow(new RuntimeException("Connection refused"));

        SentimentResult result = service.analyze("삼성전자", "삼성전자 실적 발표");

        assertThat(result.getLabel()).isEqualTo("NEUTRAL");
        assertThat(result.getScore()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("점수는 항상 -1.0~1.0 범위여야 한다")
    void score_is_always_within_range() {
        AppProperties.Anthropic anthropic = new AppProperties.Anthropic();
        anthropic.setApiKey("");
        appProperties.setAnthropic(anthropic);

        SentimentResult result = service.analyze("테스트", "테스트 뉴스");

        assertThat(result.getScore())
                .isGreaterThanOrEqualTo(-1.0)
                .isLessThanOrEqualTo(1.0);
    }
}