package com.project.Chok.service;

import com.project.Chok.config.AppProperties;
import com.project.Chok.dto.NewsArticle;
import com.project.Chok.dto.SentimentResult;
import com.project.Chok.service.sentiment.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * 뉴스 헤드라인 감성분석. 실제 LLM 호출/응답 파싱은 {@link LlmProvider} 구현체(프로바이더별)에
 * 위임하고, 여기서는 프롬프트 작성 + 프로바이더 선택 + 결과 JSON 파싱 + 예외 시 중립 폴백만
 * 담당한다. 어느 프로바이더를 쓰든 이 로직은 그대로 재사용된다.
 *
 * LLM 호출 동시성은 chok.sentiment.max-concurrent-calls로 별도 제한한다. 종목 분석
 * 병렬도(chok.analysis.parallelism)를 올려도 외부 LLM API로 나가는 동시 요청 수는
 * 이 상한을 넘지 않는다 (레이트리밋 대응). 종목당 뉴스는 {@link #analyzeBatch}로 한 번에
 * 묶어서 보내므로, 이 상한은 사실상 "종목 단위" 동시 호출 수를 의미하게 된다.
 */
@Service
public class SentimentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisService.class);

    private static final String SYSTEM_PROMPT = """
            한국 주식시장 뉴스의 재료성(materiality)을 평가하는 애널리스트입니다. 판단 기준은
            "톤이 긍정적인가"가 아니라 "애널리스트의 이익 추정치·현금흐름 전망을 실제로 바꿀
            구체적 정보인가"입니다.

            JSON으로만 응답 (마크다운 금지):
            {"score": -1.0~1.0, "label": "POSITIVE|NEUTRAL|NEGATIVE", "summary": "15단어 이내, 재료성 판단 근거만"}

            점수 기준:
            - |0.6| 이상: 구체적·즉각적 재무 임팩트만 (실적 발표/서프라이즈(수치 포함), 대형
              수주/계약(규모 명시), M&A, 자사주 매입/소각, 정책·규제 승인/불허, 주요 공급망
              확보/상실, 사업화 임박 기술/특허, CEO·경영진 사법 리스크(횡령·배임·기소·구속 등
              금액·직위가 구체적인 경우))
            - |0.2| 이하(기본값): 톤과 무관하게 재무 근거 없는 뉴스 (브랜드/ESG/사회공헌 등
              PR성, "~기대된다"류 막연한 전망, 규모·실행 불확실한 초기 MOU)
            - 0.2~0.6: 협력·계약은 있으나 매출 기여 규모·시점 불명확 → 보수적으로 채점

            예시:
            - "사회 공헌으로 브랜드 이미지 개선 기대" → 0.1~0.2 (PR성)
            - "한온시스템, 한국타이어 유통망 활용 신사업 발표" → 0.2~0.3 (규모 불명확)
            - "삼성전자 3분기 영업이익 전년比 40%↑, 컨센서스 상회" → 0.7~0.9 (수치+즉각적)
            - "OO그룹 회장, 1500억대 배임 혐의로 구속기소" → -0.6~-0.8 (경영진 리스크, 구체적)
            """;

    private final Map<String, LlmProvider> providers;
    private final AppProperties appProperties;
    private final Semaphore concurrencyLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SentimentAnalysisService(Map<String, LlmProvider> providers, AppProperties appProperties) {
        this.providers = providers;
        this.appProperties = appProperties;
        int limit = Math.max(1, appProperties.getSentiment().getMaxConcurrentCalls());
        this.concurrencyLimiter = new Semaphore(limit);
    }

    public SentimentResult analyze(String stockName, String headline) {
        LlmProvider provider = resolveProvider();
        if (provider == null) {
            return SentimentResult.neutral("프로바이더 설정 오류");
        }
        if (!provider.isConfigured()) {
            log.warn("{} API 키 미설정 - 감성 분석 생략", appProperties.getSentiment().getProvider());
            return SentimentResult.neutral("API 키 미설정");
        }

        String userMessage = "종목명: " + stockName + "\n뉴스 헤드라인: " + headline;

        String rawText;
        try {
            rawText = callProvider(provider, userMessage, computeMaxTokens(1), "headline=" + headline);
        } catch (ProviderCallException e) {
            return SentimentResult.neutral(e.getMessage());
        }
        return parseResult(rawText);
    }

    /**
     * 종목의 뉴스 헤드라인 여러 건을 한 번의 LLM 호출로 분석한다. 반환 리스트는 articles와
     * 같은 순서/크기를 보장한다 (LLM 응답이 일부 누락되거나 순서가 어긋나도 index로 매칭).
     * articles가 비어있으면 호출 자체를 생략하고 빈 리스트를 반환한다.
     */
    public List<SentimentResult> analyzeBatch(String stockName, List<NewsArticle> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }

        LlmProvider provider = resolveProvider();
        if (provider == null) {
            return neutralList(articles.size(), "프로바이더 설정 오류");
        }
        if (!provider.isConfigured()) {
            log.warn("{} API 키 미설정 - 감성 분석 생략", appProperties.getSentiment().getProvider());
            return neutralList(articles.size(), "API 키 미설정");
        }

        String userMessage = buildBatchUserMessage(stockName, articles);

        String rawText;
        try {
            rawText = callProvider(provider, userMessage, computeMaxTokens(articles.size()),
                    "종목=" + stockName + ", 건수=" + articles.size());
        } catch (ProviderCallException e) {
            return neutralList(articles.size(), e.getMessage());
        }
        return parseBatchResult(rawText, articles.size());
    }

    private LlmProvider resolveProvider() {
        String providerName = appProperties.getSentiment().getProvider();
        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            log.error("알 수 없는 sentiment provider 설정: {}", providerName);
        }
        return provider;
    }

    /** provider.chat() 실패 사유를 호출자에게 전달하기 위한 체크 예외. */
    private static class ProviderCallException extends Exception {
        ProviderCallException(String reason) {
            super(reason);
        }
    }

    /** provider.chat()을 동시성 상한 안에서 호출한다. 실패 시 사유를 담아 예외를 던진다. */
    private String callProvider(LlmProvider provider, String userMessage, int maxTokens, String logContext)
            throws ProviderCallException {
        String providerName = appProperties.getSentiment().getProvider();
        try {
            concurrencyLimiter.acquire();
            try {
                log.info("{} LLM 호출 시작 ({}, maxTokens={})", providerName, logContext, maxTokens);
                return provider.chat(SYSTEM_PROMPT, userMessage, maxTokens);
            } finally {
                concurrencyLimiter.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException("중단됨");
        } catch (Exception e) {
            log.error("{} API 호출 실패 ({}): {}", providerName, logContext, e.getMessage());
            throw new ProviderCallException("API 호출 오류");
        }
    }

    /**
     * max_tokens는 비용 절감 수단이 아니라 안전장치(응답이 잘려서 파싱 실패하는 것 방지)다.
     * 배치 크기에 비례해 여유를 두되 상하한을 걸어둔다.
     */
    private static int computeMaxTokens(int articleCount) {
        int estimated = 200 + articleCount * 120;
        return Math.max(300, Math.min(3000, estimated));
    }

    private String buildBatchUserMessage(String stockName, List<NewsArticle> articles) {
        StringBuilder sb = new StringBuilder();
        sb.append("종목명: ").append(stockName).append("\n");
        sb.append("아래 ").append(articles.size()).append("건의 뉴스 헤드라인을 각각 독립적으로 분석하세요.\n\n");
        for (int i = 0; i < articles.size(); i++) {
            sb.append('[').append(i + 1).append("] ").append(articles.get(i).getHeadline()).append('\n');
        }
        sb.append("""

                JSON으로만 응답 (마크다운 금지). results 개수는 헤드라인 개수와 같아야 하고
                각 index는 위 번호와 일치해야 합니다:
                {"results": [{"index": 1, "score": -1.0~1.0, "label": "POSITIVE|NEUTRAL|NEGATIVE", "summary": "15단어 이내"}, ...]}
                """);
        return sb.toString();
    }

    private SentimentResult parseResult(String rawText) {
        try {
            String text = stripCodeFence(rawText);
            JsonNode result = objectMapper.readTree(text);
            return toSentimentResult(result);
        } catch (Exception e) {
            log.error("응답 파싱 실패: {}", e.getMessage());
            return SentimentResult.neutral("응답 파싱 실패");
        }
    }

    private List<SentimentResult> parseBatchResult(String rawText, int expectedCount) {
        SentimentResult[] results = new SentimentResult[expectedCount];

        try {
            String text = stripCodeFence(rawText);
            JsonNode root = objectMapper.readTree(text);
            JsonNode array = root.path("results");

            if (!array.isArray()) {
                throw new IllegalStateException("results가 JSON 배열이 아님");
            }

            for (JsonNode node : array) {
                int index = node.path("index").asInt(-1);
                int pos = index - 1;
                if (pos < 0 || pos >= expectedCount) {
                    log.warn("배치 응답의 index가 범위를 벗어남: {}", index);
                    continue;
                }
                results[pos] = toSentimentResult(node);
            }
        } catch (Exception e) {
            log.error("배치 응답 파싱 실패: {}", e.getMessage());
            return neutralList(expectedCount, "응답 파싱 실패");
        }

        for (int i = 0; i < expectedCount; i++) {
            if (results[i] == null) {
                results[i] = SentimentResult.neutral("배치 응답에 누락됨");
            }
        }
        return List.of(results);
    }

    private SentimentResult toSentimentResult(JsonNode node) {
        double score = Math.max(-1.0, Math.min(1.0, node.path("score").asDouble(0.0)));
        String label = node.path("label").asText("NEUTRAL");
        String summary = node.path("summary").asText("");
        return new SentimentResult(score, label, summary);
    }

    private String stripCodeFence(String rawText) {
        return rawText.replaceAll("```json", "").replaceAll("```", "").trim();
    }

    private List<SentimentResult> neutralList(int size, String reason) {
        List<SentimentResult> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(SentimentResult.neutral(reason));
        }
        return list;
    }
}
