package com.project.Chok.service.sentiment;

import com.project.Chok.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/** chok.anthropic.* 설정을 사용하는 Anthropic Messages API 연동. */
@Component("anthropic")
public class AnthropicProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AnthropicProvider(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.appProperties = appProperties;
    }

    @Override
    public boolean isConfigured() {
        String apiKey = appProperties.getAnthropic().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chat(String systemPrompt, String userMessage, int maxTokens) {
        AppProperties.Anthropic anthropic = appProperties.getAnthropic();

        Map<String, Object> requestBody = Map.of(
                "model", anthropic.getModel(),
                "max_tokens", maxTokens,
                "system", systemPrompt,
                "messages", List.of(
                        Map.of("role", "user", "content", userMessage)
                )
        );

        String responseBody = webClient.post()
                .uri(anthropic.getBaseUrl())
                .header("x-api-key", anthropic.getApiKey())
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        JsonNode root = parseEnvelope(responseBody);
        logUsage(root);
        return extractText(root);
    }

    private JsonNode parseEnvelope(String responseBody) {
        try {
            return objectMapper.readTree(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic 응답 envelope 파싱 실패: " + e.getMessage(), e);
        }
    }

    private void logUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) return;
        int input = usage.path("input_tokens").asInt(-1);
        int output = usage.path("output_tokens").asInt(-1);
        int total = (input >= 0 && output >= 0) ? input + output : -1;
        log.info("anthropic 토큰 사용량: prompt={}, completion={}, total={}", input, output, total);
    }

    private String extractText(JsonNode root) {
        try {
            return root.path("content").get(0).path("text").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("Anthropic 응답 envelope 파싱 실패: " + e.getMessage(), e);
        }
    }
}
