package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.service.sentiment.OpenAiProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OpenAiProvider의 요청 형식(모델/헤더/메시지)과 응답 envelope 파싱을 검증한다. */
@ExtendWith(MockitoExtension.class)
class OpenAiProviderTest {

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
    private OpenAiProvider provider;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        AppProperties.OpenAi openai = new AppProperties.OpenAi();
        openai.setApiKey("test-api-key");
        openai.setModel("gpt-4o-mini");
        openai.setBaseUrl("https://api.openai.com/v1/chat/completions");
        appProperties.setOpenai(openai);

        provider = new OpenAiProvider(webClient, appProperties);
    }

    @Test
    @DisplayName("API 키가 없으면 isConfigured()가 false")
    void not_configured_when_api_key_blank() {
        AppProperties.OpenAi openai = new AppProperties.OpenAi();
        openai.setApiKey("");
        appProperties.setOpenai(openai);

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    @DisplayName("API 키가 있으면 isConfigured()가 true")
    void configured_when_api_key_present() {
        assertThat(provider.isConfigured()).isTrue();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Bearer 토큰 헤더와 OpenAI 메시지 형식으로 요청을 보낸다")
    void sends_openai_shaped_request() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri("https://api.openai.com/v1/chat/completions")).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any(String[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(requestBodySpec.bodyValue(bodyCaptor.capture())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("""
                {"choices":[{"message":{"role":"assistant","content":"{}"}}]}
                """));

        provider.chat("system prompt", "user message", 777);

        verify(requestBodySpec).header(eq("Authorization"), eq("Bearer test-api-key"));
        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body.get("model")).isEqualTo("gpt-4o-mini");
        assertThat(body.get("max_tokens")).isEqualTo(777);
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertThat(messages.get(0)).containsEntry("role", "system").containsEntry("content", "system prompt");
        assertThat(messages.get(1)).containsEntry("role", "user").containsEntry("content", "user message");
    }

    @Test
    @DisplayName("choices[0].message.content를 원문 텍스트로 추출한다")
    void extracts_text_from_choices_message_content() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any(String[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("""
                {
                    "choices": [
                        {
                            "message": {
                                "role": "assistant",
                                "content": "{\\"score\\": 0.8, \\"label\\": \\"POSITIVE\\", \\"summary\\": \\"호재\\"}"
                            }
                        }
                    ]
                }
                """));

        String text = provider.chat("system", "user", 500);

        assertThat(text).isEqualTo("{\"score\": 0.8, \"label\": \"POSITIVE\", \"summary\": \"호재\"}");
    }

    @Test
    @DisplayName("응답 envelope이 예상 형식이 아니면 예외를 던진다")
    void throws_when_envelope_is_malformed() {
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), any(String[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just("이건 JSON이 아님"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> provider.chat("system", "user", 500));
    }
}
