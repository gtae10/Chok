package com.project.Chok.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.NewsSentiment;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.domain.StockReport;
import com.project.Chok.repository.NewsSentimentRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.repository.StockReportRepository;
import com.project.Chok.service.sentiment.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 종목별 지표(기술/감성/상승확률)를 종합해 펀드매니저 리포트 스타일의 2~3문장 설명을 생성한다.
 * 실제 LLM 호출/응답 파싱은 {@link LlmProvider}(감성분석과 동일한 프로바이더)에 위임한다.
 *
 * 비용 관리: 감성분석의 배치/dedup과 같은 원칙을 "이 리포트가 필요한 시점에만 생성"으로
 * 적용한다 - 매일 전종목을 미리 만들지 않고, 종목 상세페이지를 실제로 열 때만(lazy) 생성하며,
 * 직전 생성 시점 대비 점수가 유의미하게 안 바뀌었으면(hasChangedMeaningfully가 false) 재생성
 * 없이 캐시(stock_reports 테이블, 종목당 최신 1건)를 그대로 재사용한다.
 */
@Service
public class StockReportService {

    private static final Logger log = LoggerFactory.getLogger(StockReportService.class);

    // 이 이상 finalScore가 움직였거나 추천 등급 자체가 바뀌면 "유의미한 변화"로 보고 재생성한다.
    // 등급 변경은 점수 변화폭과 무관하게 항상 재생성 대상 - 어조가 등급 경계를 넘나들 수 있어서.
    private static final double SCORE_CHANGE_THRESHOLD = 3.0;

    private static final String SYSTEM_PROMPT = """
            정량 지표를 종합해 간결한 리포트를 쓰는 애널리스트입니다. 절대 원칙: 문장의 확신도가
            실제 근거의 강도를 넘어서면 안 됩니다.

            - 등급이 STRONG_BUY/STRONG_SELL이고 기술·감성 지표가 같은 방향으로 일치할 때만
              확신 있는 어조("~할 가능성이 높습니다", "뚜렷한 상승 신호")를 쓰세요.
            - HOLD 등급이거나, 기술점수와 감성점수가 서로 엇갈리거나, 상승확률이 학습된 모델이
              아니라 휴리스틱 추정치일 뿐이면 반드시 "신호가 혼재되어 있다", "방향성이 뚜렷하지
              않다", "판단을 보류하는 것이 합리적이다" 같은 신중한 표현을 쓰세요.
            - 입력에 없는 정보를 지어내지 마세요. 관련 뉴스가 없다고 나오면 "특별한 뉴스 재료는
              없다"고만 쓰세요.
            - 이건 투자 조언이 아니라 "현재 수집된 지표 요약"이라는 전제를 벗어나지 마세요.

            JSON으로만 응답 (마크다운 금지): {"report": "2~3문장, 한국어"}
            """;

    private final RecommendationRepository recommendationRepository;
    private final NewsSentimentRepository newsSentimentRepository;
    private final StockReportRepository stockReportRepository;
    private final Map<String, LlmProvider> providers;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockReportService(RecommendationRepository recommendationRepository,
                               NewsSentimentRepository newsSentimentRepository,
                               StockReportRepository stockReportRepository,
                               Map<String, LlmProvider> providers,
                               AppProperties appProperties) {
        this.recommendationRepository = recommendationRepository;
        this.newsSentimentRepository = newsSentimentRepository;
        this.stockReportRepository = stockReportRepository;
        this.providers = providers;
        this.appProperties = appProperties;
    }

    public record ReportResult(String reportText, boolean cached, LocalDateTime generatedAt) {}

    public ReportResult getOrGenerateReport(String ticker) {
        List<Recommendation> latestList = recommendationRepository.findHistoryByTicker(ticker, PageRequest.of(0, 1));
        if (latestList.isEmpty()) {
            return new ReportResult("아직 분석 데이터가 없어 리포트를 생성할 수 없습니다.", false, null);
        }
        Recommendation latest = latestList.get(0);

        StockReport cached = stockReportRepository.findById(ticker).orElse(null);
        if (cached != null && !hasChangedMeaningfully(latest, cached)) {
            log.info("리포트 캐시 재사용 (ticker={}, 직전 생성={})", ticker, cached.getGeneratedAt());
            return new ReportResult(cached.getReportText(), true, cached.getGeneratedAt());
        }

        String reportText = generateReport(latest);

        StockReport entity = cached != null ? cached : new StockReport();
        entity.setTicker(ticker);
        entity.setReportText(reportText);
        LocalDateTime now = LocalDateTime.now();
        entity.setGeneratedAt(now);
        entity.setBasedOnDate(latest.getRecDate());
        entity.setBasedOnFinalScore(latest.getFinalScore());
        entity.setBasedOnRecommendation(latest.getRecommendation());
        stockReportRepository.save(entity);

        return new ReportResult(reportText, false, now);
    }

    private boolean hasChangedMeaningfully(Recommendation latest, StockReport cached) {
        if (cached.getBasedOnFinalScore() == null || cached.getBasedOnRecommendation() == null) return true;
        if (!cached.getBasedOnRecommendation().equals(latest.getRecommendation())) return true;
        return Math.abs(latest.getFinalScore() - cached.getBasedOnFinalScore()) > SCORE_CHANGE_THRESHOLD;
    }

    private String generateReport(Recommendation r) {
        LlmProvider provider = providers.get(appProperties.getSentiment().getProvider());
        if (provider == null || !provider.isConfigured()) {
            log.warn("리포트 생성용 LLM 프로바이더 미설정 - 리포트 생략 (ticker={})", r.getTicker());
            return "AI 리포트를 생성할 수 없습니다 (LLM 프로바이더 미설정).";
        }

        String userMessage = buildUserMessage(r);
        try {
            String rawText = provider.chat(SYSTEM_PROMPT, userMessage, 400);
            return parseReport(rawText);
        } catch (Exception e) {
            log.error("리포트 생성 실패 (ticker={}): {}", r.getTicker(), e.getMessage());
            return "AI 리포트 생성 중 오류가 발생했습니다.";
        }
    }

    private String buildUserMessage(Recommendation r) {
        StringBuilder sb = new StringBuilder();
        sb.append("종목명: ").append(r.getName()).append(" (").append(r.getTicker()).append(")\n");
        sb.append("기술점수: ").append(r.getTechnicalScore()).append("/100\n");
        sb.append("감성점수(뉴스): ").append(r.getSentimentScore()).append(" (-1~1)\n");
        sb.append("종합점수: ").append(r.getFinalScore()).append("/100\n");
        sb.append("추천 등급: ").append(r.getRecommendation());
        if (r.getRecommendationNuance() != null) {
            sb.append(" (세부: ").append(r.getRecommendationNuance()).append(")");
        }
        sb.append('\n');

        if (r.getRiseProbability() != null) {
            sb.append("상승확률: ").append(r.getRiseProbability()).append("% (출처: ")
              .append("MODEL".equals(r.getProbabilitySource()) ? "학습된 모델" : "지표 종합 추정치(휴리스틱, 통계 검증 안 됨)")
              .append(r.getProbabilityHorizonDays() != null ? ", " + r.getProbabilityHorizonDays() + "영업일 기준" : "")
              .append(")\n");
        }
        if (r.getNotableHorizonDays() != null) {
            sb.append("유력 구간: 약 ").append(r.getNotableHorizonApproxDate()).append(" 전후 (")
              .append(r.getNotableHorizonProbability()).append("%) - 통계 검증된 예측은 아님\n");
        }

        sb.append("지표 상세: ").append(r.getReason()).append('\n');

        List<NewsSentiment> recentNews = newsSentimentRepository.findByTickerSince(
                r.getTicker(), r.getRecDate().minusDays(appProperties.getAnalysis().getNewsLookbackDays()),
                PageRequest.of(0, 5));
        if (recentNews.isEmpty()) {
            sb.append("최근 관련 뉴스: 없음\n");
        } else {
            sb.append("최근 관련 뉴스(재료성 높은 순, 최대 3건):\n");
            recentNews.stream()
                    .sorted(Comparator.comparingDouble((NewsSentiment n) -> Math.abs(orZero(n.getSentimentScore()))).reversed())
                    .limit(3)
                    .forEach(n -> sb.append("- [").append(n.getSentimentLabel()).append("] ")
                            .append(n.getHeadline()).append(" - ").append(n.getSummary()).append('\n'));
        }

        sb.append("\n위 지표를 종합해 2~3문장짜리 리포트를 작성하세요.");
        return sb.toString();
    }

    private double orZero(Double v) { return v == null ? 0.0 : v; }

    private String parseReport(String rawText) {
        try {
            String text = rawText.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode root = objectMapper.readTree(text);
            String report = root.path("report").asText("");
            return report.isBlank() ? "리포트 생성 결과가 비어 있습니다." : report;
        } catch (Exception e) {
            log.error("리포트 응답 파싱 실패: {}", e.getMessage());
            return "리포트 응답을 해석하지 못했습니다.";
        }
    }
}
