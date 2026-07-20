package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.SentimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class SentimentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    private final WebClient webClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            당신은 한국 주식시장 뉴스 감성 분석 전문가입니다.
            주어진 뉴스 헤드라인이 해당 종목의 주가에 미칠 영향을 분석하세요.

            반드시 아래 JSON 형식으로만 응답하세요. 마크다운 코드블록 없이 순수 JSON만 출력하세요:
            {"score": -1.0~1.0 사이 숫자, "label": "POSITIVE 또는 NEUTRAL 또는 NEGATIVE", "summary": "한 문장 근거"}

            점수 기준:
            - 1.0에 가까울수록 강한 호재 (실적 개선, 신규 계약, 기술 혁신 등)
            - -1.0에 가까울수록 강한 악재 (실적 악화, 소송, 규제 리스크 등)
            - 0에 가까울수록 중립적 정보성 뉴스
            """;

    public SentimentAnalysisService(WebClient webClient, AppProperties appProperties) {
        this.webClient = webClient;
        this.appProperties = appProperties;
    }

    public SentimentResult analyze(String stockName, String headline) {
        AppProperties.Anthropic anthropic = appProperties.getAnthropic();

        if (anthropic.getApiKey() == null || anthropic.getApiKey().isBlank()) {
            log.warn("ANTHROPIC_API_KEY 미설정 - 감성 분석 생략");
            return SentimentResult.neutral("API 키 미설정");
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", anthropic.getModel(),
                    "max_tokens", 300,
                    "system", SYSTEM_PROMPT,
                    "messages", List.of(
                            Map.of("role", "user",
                                   "content", "종목명: " + stockName + "\n뉴스 헤드라인: " + headline)
                    )
            );

            String responseBody = webClient.post()
                    .uri(anthropic.getBaseUrl())
                    .header("x-api-key", anthropic.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(responseBody);

        } catch (Exception e) {
            log.error("Claude API 호출 실패 (headline={}): {}", headline, e.getMessage());
            return SentimentResult.neutral("API 호출 오류");
        }
    }

    private SentimentResult parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String text = root.path("content").get(0).path("text").asText("");
            text = text.replaceAll("```json", "").replaceAll("```", "").trim();

            JsonNode result = objectMapper.readTree(text);
            double score  = Math.max(-1.0, Math.min(1.0, result.path("score").asDouble(0.0)));
            String label  = result.path("label").asText("NEUTRAL");
            String summary = result.path("summary").asText("");

            return new SentimentResult(score, label, summary);

        } catch (Exception e) {
            log.error("Claude 응답 파싱 실패: {}", e.getMessage());
            return SentimentResult.neutral("응답 파싱 실패");
        }
    }
}
