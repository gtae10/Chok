package com.project.Chok.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.FeedbackLog;
import com.project.Chok.domain.Recommendation;
import com.project.Chok.dto.ChatMessage;
import com.project.Chok.repository.FeedbackLogRepository;
import com.project.Chok.repository.RecommendationRepository;
import com.project.Chok.service.sentiment.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 사이트 안내 + 사용자 피드백을 받는 AI 챗봇. 기존 LlmProvider(OpenAI/Anthropic 추상화,
 * 감성분석/AI리포트에서 쓰던 것과 동일)를 그대로 재사용한다.
 *
 * 로그인이 없어 사용자별 제한이 불가능하므로 클라이언트 IP 기준 요청 빈도 제한만 건다
 * (chok.chat.* - 완벽한 남용 방지는 아니지만 상식적인 상한선). 대화 이력 자체는 서버에
 * 영구 저장하지 않고(클라이언트가 매 요청마다 함께 보내는 값을 그때그때만 사용) 그
 * 대신 사용자가 남긴 피드백 내용은 개발자가 나중에 검토할 수 있도록 feedback_logs에
 * 남긴다 (개인정보 없음 - conversationId는 신원과 무관한 클라이언트 생성 임의 문자열).
 */
@Service
public class ChatFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(ChatFeedbackService.class);

    private static final String SYSTEM_PROMPT = """
            당신은 한국 주식 스크리닝 서비스 '촉(Chok)'의 AI 안내 챗봇입니다.
            이 서비스는 KOSPI/KOSDAQ 시가총액 상위 종목의 기술적 지표(이동평균/RSI/MACD/
            볼린저밴드/거래량)와 뉴스 감성분석을 결합해 종목별 기술점수/감성점수/종합점수,
            추천 등급(STRONG_BUY~STRONG_SELL)을 보여주는 참고용 도구입니다. 수익을
            보장하지 않습니다.

            화면에 실제로 표시되는 핵심 지표 체계 - 사용자가 물으면 아래 용어를 그대로
            정확히 써서 설명하세요(뭉뚱그리지 말 것):

            - 태그: 예측이 아니라 "지금 이 종목이 이런 상태다"라는 사실 표시입니다.
              모멘텀 상위 20%(약 1년 전~1개월 전 수익률이 그날 분석 종목 중 상위 20%),
              저변동성(최근 60영업일 등락 폭이 하위 20%), 주식수 증가/감소(1년 전보다
              주식수가 2% 넘게 늘거나 줄었음 - 유상증자·합병, 자사주 소각 등),
              재료성 호재/악재(최근 뉴스 중 구체적 재무 영향이 있는 뉴스)가 있습니다.
              태그별 실제 성과는 기록을 쌓아 검증하는 중이며, 태그를 매수/매도 신호로
              설명하지 마세요.
            - 상승확률: 과거 5년 데이터로 검증했을 때 무작위 수준을 넘는 예측력이
              확인되지 않아 화면에서 내렸습니다. 사용자가 물으면 그 사실을 그대로
              설명하세요.
            - 감성점수: 뉴스 헤드라인의 어조(긍정적/부정적 말투)가 아니라 "재료성"
              (materiality) - 즉 그 뉴스가 실제로 주가에 영향을 줄 만한 구체적 내용을
              담고 있는지를 기준으로 판단합니다. 단순히 우호적인 표현을 썼다고 긍정
              점수를 주지 않습니다.
            - 모든 지표는 통계적으로 검증된 확정 예측이 아니라 참고용 보조 지표입니다.

            역할:
            1. 사용자가 이 서비스의 점수/등급/확률이 무엇을 의미하는지 물으면, 위 용어를
               정확히 사용해 구체적으로 설명하세요("확률 관련 지표가 있다" 식으로
               뭉뚱그리지 말고, 학습/추정 구분과 N영업일 기준처럼 화면에 실제 보이는
               표현을 그대로 쓰세요).
            2. 사용자의 의견/불편사항/개선 요청은 편하게 받고 정중하게 감사를 표하세요.
            3. 종목 추천 요청("뭐 살까요", "추천해줘", "이 종목 어때요")에는 사용자 메시지에
               붙은 [분석 데이터]만 근거로 "관심 후보"를 최대 5개까지 제시할 수 있습니다.
               - 후보마다 근거가 된 지표(종합/기술/감성점수, 태그, 모멘텀, 52주 신고가 대비
                 위치)를 데이터에 있는 값 그대로 인용하세요.
               - [분석 데이터]에 없는 정보(실적, 재무, 외부 뉴스, 목표가, 미래 주가 전망)는
                 쓰지 마세요. 데이터에 없는 종목을 물으면 이 서비스의 분석 대상이 아니라고
                 답하세요.
               - 수익을 보장하거나 확정적으로 단정하는 표현, 매수 가격·수량·시점 지정은
                 금지입니다. "사세요/파세요"가 아니라 "이 지표들이 해당됩니다" 식으로
                 데이터를 보여주세요.
               - 추천에는 반드시 검증 상태를 한 문장 포함하세요: 지금의 점수와 태그는 과거
                 5년 백테스트에서 검증 기준을 통과하지 못했으며, 그래서 예측이 아니라
                 참고용이라는 점입니다.
            4. 추천이 아닌 일반 질문에는 이 항목을 쓰지 말고 1~2번대로 답하세요.

            JSON으로만 응답 (마크다운 금지): {"reply": "한국어. 일반 질문은 2~4문장, 종목 추천은 후보별 한 줄씩 최대 8문장"}
            """;

    // 추천이 섞인 답변 끝에 서버가 항상 붙인다 - LLM이 빼먹어도 화면에는 나가도록 코드로 보장
    static final String DISCLAIMER = "\n\n※ 투자 참고용 안내: 수집된 데이터를 정리한 것일 뿐 투자 권유나 수익 보장이 아닙니다. "
            + "점수와 태그는 과거 검증에서 기준을 통과하지 못한 보조 지표이며, 투자 판단과 결과의 책임은 본인에게 있습니다.";
    private static final int CONTEXT_TOP = 30;
    private static final int CONTEXT_MAX = 50;
    private static final Map<String, String> TAG_LABEL = Map.of(
            "MOMENTUM_TOP20", "모멘텀상위20%", "LOW_VOL20", "저변동성", "ISSUANCE_UP", "주식수증가",
            "BUYBACK", "주식수감소", "NEWS_POS", "재료성호재", "NEWS_NEG", "재료성악재", "BREAKOUT_52W", "52주신고가돌파");

    private final Map<String, LlmProvider> providers;
    private final AppProperties appProperties;
    private final FeedbackLogRepository feedbackLogRepository;
    private final RecommendationRepository recommendationRepository;
    private final TagService tagService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, RateWindow> rateLimiter = new ConcurrentHashMap<>();

    public ChatFeedbackService(Map<String, LlmProvider> providers, AppProperties appProperties,
                               FeedbackLogRepository feedbackLogRepository,
                               RecommendationRepository recommendationRepository, TagService tagService) {
        this.providers = providers;
        this.appProperties = appProperties;
        this.feedbackLogRepository = feedbackLogRepository;
        this.recommendationRepository = recommendationRepository;
        this.tagService = tagService;
    }

    public record ChatResult(String reply, boolean rateLimited) {}

    public ChatResult chat(String clientIp, String conversationId, String message, List<ChatMessage> history) {
        AppProperties.Chat cfg = appProperties.getChat();

        if (!tryConsume(clientIp, cfg)) {
            log.info("피드백 챗봇 요청 제한 초과 (ip={})", clientIp);
            return new ChatResult("짧은 시간에 너무 많은 메시지를 보내셨어요. 잠시 후 다시 시도해주세요.", true);
        }

        String trimmedMessage = message == null ? "" : message.trim();
        if (trimmedMessage.isEmpty()) {
            return new ChatResult("메시지를 입력해주세요.", false);
        }
        if (trimmedMessage.length() > cfg.getMaxMessageLength()) {
            return new ChatResult("메시지가 너무 깁니다 (" + cfg.getMaxMessageLength() + "자 이내로 입력해주세요).", false);
        }

        LlmProvider provider = providers.get(appProperties.getSentiment().getProvider());
        if (provider == null || !provider.isConfigured()) {
            log.warn("피드백 챗봇용 LLM 프로바이더 미설정");
            return new ChatResult("현재 챗봇을 사용할 수 없습니다 (LLM 프로바이더 미설정).", false);
        }

        List<Recommendation> recs = List.of();
        Map<String, List<String>> tags = Map.of();
        LocalDate dataDate = recommendationRepository.findLatestRecDate();
        if (dataDate != null) {
            recs = recommendationRepository.findByRecDateOrderByFinalScoreDesc(dataDate);
            tags = tagService.tagsOn(dataDate);
        }
        List<Recommendation> picked = pickForContext(recs, tags, trimmedMessage);
        String userMessage = buildStockContext(dataDate, picked, tags) + buildTranscript(history, trimmedMessage, cfg);
        String reply;
        try {
            String rawText = provider.chat(SYSTEM_PROMPT, userMessage, cfg.getMaxTokens());
            reply = parseReply(rawText);
            if (mentionsAny(reply, picked)) reply += DISCLAIMER;
        } catch (Exception e) {
            log.error("피드백 챗봇 응답 생성 실패: {}", e.getMessage());
            reply = "죄송해요, 지금은 답변을 생성하지 못했어요. 잠시 후 다시 시도해주세요.";
        }

        saveFeedbackLog(conversationId, trimmedMessage, reply);
        return new ChatResult(reply, false);
    }

    /** 챗봇에 넘길 종목: 사용자가 이름으로 언급한 종목 -> 종합점수 상위 -> 태그가 붙은 종목 순, 최대 CONTEXT_MAX개. */
    static List<Recommendation> pickForContext(List<Recommendation> recs, Map<String, List<String>> tags, String message) {
        Map<String, Recommendation> picked = new LinkedHashMap<>();
        for (Recommendation r : recs) {
            if (r.getName() != null && message.contains(r.getName())) picked.put(r.getTicker(), r);
        }
        for (int i = 0; i < Math.min(CONTEXT_TOP, recs.size()); i++) picked.putIfAbsent(recs.get(i).getTicker(), recs.get(i));
        for (Recommendation r : recs) {
            if (picked.size() >= CONTEXT_MAX) break;
            if (!tags.getOrDefault(r.getTicker(), List.of()).isEmpty()) picked.putIfAbsent(r.getTicker(), r);
        }
        return new ArrayList<>(picked.values());
    }

    /** 사용자 메시지 앞에 붙는 [분석 데이터] 블록 - LLM이 추천할 때 쓸 수 있는 유일한 근거. */
    static String buildStockContext(LocalDate date, List<Recommendation> picked, Map<String, List<String>> tags) {
        if (date == null || picked.isEmpty()) return "[분석 데이터]\n아직 분석된 데이터가 없습니다.\n\n";
        StringBuilder sb = new StringBuilder("[분석 데이터] 기준일 ").append(date)
                .append(" (종합점수 순, 전 종목이 아닌 일부)\n");
        for (Recommendation r : picked) {
            sb.append("- ").append(r.getName()).append('(').append(r.getTicker()).append(", ").append(r.getMarket())
              .append(") 종합 ").append(fmt(r.getFinalScore())).append(" 기술 ").append(fmt(r.getTechnicalScore()))
              .append(" 감성 ").append(fmt(r.getSentimentScore()));
            List<String> t = tags.getOrDefault(r.getTicker(), List.of());
            if (!t.isEmpty()) sb.append(" | 태그: ").append(String.join(", ", t.stream().map(x -> TAG_LABEL.getOrDefault(x, x)).toList()));
            if (r.getMomentum12m() != null) sb.append(" | 12-1개월 모멘텀 ").append(fmt(r.getMomentum12m())).append('%');
            if (r.getHigh52wGap() != null) sb.append(" | 52주 신고가 대비 ").append(fmt(r.getHigh52wGap())).append('%');
            sb.append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String fmt(Double v) { return v == null ? "-" : String.format("%.1f", v); }

    private static boolean mentionsAny(String reply, List<Recommendation> picked) {
        return picked.stream().anyMatch(r -> r.getName() != null && r.getName().length() >= 2 && reply.contains(r.getName()));
    }

    /** 클라이언트 IP당 windowMinutes 동안 maxRequestsPerWindow회 초과 시 거부하는 고정 윈도우 제한. */
    private boolean tryConsume(String clientIp, AppProperties.Chat cfg) {
        long now = System.currentTimeMillis();
        long windowMs = cfg.getWindowMinutes() * 60_000L;
        RateWindow window = rateLimiter.compute(clientIp, (ip, existing) -> {
            if (existing == null || now - existing.windowStart > windowMs) {
                return new RateWindow(now, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= cfg.getMaxRequestsPerWindow();
    }

    /**
     * chat() 인터페이스가 단일 사용자 메시지만 받으므로, 멀티턴 맥락은 텍스트 트랜스크립트로
     * 직렬화해 하나의 사용자 메시지로 합친다 (인터페이스 변경 없이 그대로 재사용하기 위함).
     * 이력 개수(maxHistoryMessages)와 각 메시지 길이(maxMessageLength) 둘 다 서버에서
     * 다시 제한한다 - 클라이언트가 무엇을 보내든 프롬프트 크기가 무한정 커지지 않게.
     */
    private String buildTranscript(List<ChatMessage> history, String latestMessage, AppProperties.Chat cfg) {
        StringBuilder sb = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            int from = Math.max(0, history.size() - cfg.getMaxHistoryMessages());
            sb.append("[이전 대화]\n");
            for (ChatMessage m : history.subList(from, history.size())) {
                String speaker = "assistant".equals(m.role()) ? "챗봇" : "사용자";
                String content = truncate(m.content(), cfg.getMaxMessageLength());
                sb.append(speaker).append(": ").append(content).append('\n');
            }
            sb.append('\n');
        }
        sb.append("[새 메시지]\n사용자: ").append(latestMessage)
          .append("\n\n위 대화 맥락을 참고해 새 메시지에 답하세요.");
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }

    private String parseReply(String rawText) {
        try {
            String text = rawText.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode root = objectMapper.readTree(text);
            String reply = root.path("reply").asText("");
            return reply.isBlank() ? "답변을 생성하지 못했어요." : reply;
        } catch (Exception e) {
            log.error("피드백 챗봇 응답 파싱 실패: {}", e.getMessage());
            return "답변을 해석하지 못했어요.";
        }
    }

    private void saveFeedbackLog(String conversationId, String userMessage, String assistantReply) {
        try {
            FeedbackLog entity = new FeedbackLog();
            entity.setConversationId(conversationId == null ? null : truncate(conversationId, 64));
            entity.setUserMessage(userMessage);
            entity.setAssistantReply(assistantReply);
            entity.setCreatedAt(LocalDateTime.now());
            feedbackLogRepository.save(entity);
        } catch (Exception e) {
            log.warn("피드백 로그 저장 실패: {}", e.getMessage());
        }
    }

    private static class RateWindow {
        final long windowStart;
        int count;
        RateWindow(long windowStart, int count) { this.windowStart = windowStart; this.count = count; }
    }
}
