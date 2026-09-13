package com.project.Chok.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.FeedbackLog;
import com.project.Chok.dto.ChatMessage;
import com.project.Chok.repository.FeedbackLogRepository;
import com.project.Chok.service.sentiment.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
            추천 등급(STRONG_BUY~STRONG_SELL), 상승확률(학습된 모델 또는 지표 종합
            추정치)을 보여주는 참고용 도구입니다. 수익을 보장하지 않습니다.

            역할:
            1. 사용자가 이 서비스의 점수/등급/확률이 무엇을 의미하는지 물으면 정확하고
               이해하기 쉽게 설명하세요.
            2. 사용자의 의견/불편사항/개선 요청은 편하게 받고 정중하게 감사를 표하세요.
            3. 절대 원칙: "이 종목을 사세요/파세요" 같은 구체적 투자 조언이나 특정 가격·
               시점을 확정적으로 예측하는 말을 하면 안 됩니다. 사이트 전체가 지켜온
               "참고용 지표이며 확정 예측이 아니다"라는 원칙을 챗봇도 동일하게 지켜야
               합니다.
            4. "이 종목 사도 되나요?" 같은 질문에는 직접 답하지 말고, 이미 화면에 표시된
               기술점수/감성점수/상승확률 등 지표를 참고하되 최종 판단은 본인 몫이라고
               안내하세요.

            JSON으로만 응답 (마크다운 금지): {"reply": "한국어 2~4문장"}
            """;

    private final Map<String, LlmProvider> providers;
    private final AppProperties appProperties;
    private final FeedbackLogRepository feedbackLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, RateWindow> rateLimiter = new ConcurrentHashMap<>();

    public ChatFeedbackService(Map<String, LlmProvider> providers, AppProperties appProperties,
                               FeedbackLogRepository feedbackLogRepository) {
        this.providers = providers;
        this.appProperties = appProperties;
        this.feedbackLogRepository = feedbackLogRepository;
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

        String userMessage = buildTranscript(history, trimmedMessage, cfg);
        String reply;
        try {
            String rawText = provider.chat(SYSTEM_PROMPT, userMessage, cfg.getMaxTokens());
            reply = parseReply(rawText);
        } catch (Exception e) {
            log.error("피드백 챗봇 응답 생성 실패: {}", e.getMessage());
            reply = "죄송해요, 지금은 답변을 생성하지 못했어요. 잠시 후 다시 시도해주세요.";
        }

        saveFeedbackLog(conversationId, trimmedMessage, reply);
        return new ChatResult(reply, false);
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
