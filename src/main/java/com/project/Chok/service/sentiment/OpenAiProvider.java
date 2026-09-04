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

/** chok.openai.* 설정을 사용하는 OpenAI Chat Completions API 연동. */
@Component("openai")
public class OpenAiProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiProvider(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.appProperties = appProperties;
    }

    @Override
    public boolean isConfigured() {
        String apiKey = appProperties.getOpenai().getApiKey();
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String chat(String systemPrompt, String userMessage, int maxTokens) {
        AppProperties.OpenAi openai = appProperties.getOpenai();

        Map<String, Object> requestBody = Map.of(
                "model", openai.getModel(),
                "max_tokens", maxTokens,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                )
        );

        String responseBody = webClient.post()
                .uri(openai.getBaseUrl())
                .header("Authorization", "Bearer " + openai.getApiKey())
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
            throw new IllegalStateException("OpenAI 응답 envelope 파싱 실패: " + e.getMessage(), e);
        }
    }

    private void logUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) return;
        log.info("openai 토큰 사용량: prompt={}, completion={}, total={}",
                usage.path("prompt_tokens").asInt(-1),
                usage.path("completion_tokens").asInt(-1),
                usage.path("total_tokens").asInt(-1));
    }

    private String extractText(JsonNode root) {
        try {
            return root.path("choices").get(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("OpenAI 응답 envelope 파싱 실패: " + e.getMessage(), e);
        }
    }
}
