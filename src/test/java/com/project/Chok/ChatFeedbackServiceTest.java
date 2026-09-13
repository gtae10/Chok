package com.project.Chok;

import com.project.Chok.config.AppProperties;
import com.project.Chok.domain.FeedbackLog;
import com.project.Chok.repository.FeedbackLogRepository;
import com.project.Chok.service.ChatFeedbackService;
import com.project.Chok.service.sentiment.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 실제 LLM 호출 없이도 지켜야 하는 안전장치들 - 로그인이 없어 사용자별 제한이 불가능한
 * 상태에서 IP 기준 요청 제한/메시지 길이 제한이 실제로 LLM 호출 자체를 막는지가 핵심.
 */
@ExtendWith(MockitoExtension.class)
class ChatFeedbackServiceTest {

    @Mock private LlmProvider llmProvider;
    @Mock private FeedbackLogRepository feedbackLogRepository;

    private AppProperties appProperties;
    private ChatFeedbackService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppProperties();
        appProperties.getSentiment().setProvider("openai");
        appProperties.getChat().setMaxRequestsPerWindow(3);
        appProperties.getChat().setWindowMinutes(10);
        appProperties.getChat().setMaxMessageLength(50);

        service = new ChatFeedbackService(Map.of("openai", llmProvider), appProperties, feedbackLogRepository);
    }

    @Test
    @DisplayName("메시지 길이 제한을 넘으면 LLM을 호출하지 않는다")
    void rejects_overly_long_message_without_calling_llm() {
        String tooLong = "가".repeat(100);

        ChatFeedbackService.ChatResult result = service.chat("1.2.3.4", "conv-1", tooLong, List.of());

        assertThat(result.reply()).contains("너무 깁니다");
        verifyNoInteractions(llmProvider);
    }

    @Test
    @DisplayName("같은 IP가 요청 상한을 넘으면 이후 요청은 LLM 호출 없이 거부된다")
    void rate_limits_by_client_ip() {
        when(llmProvider.isConfigured()).thenReturn(true);
        when(llmProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("{\"reply\": \"답변입니다.\"}");

        for (int i = 0; i < 3; i++) {
            ChatFeedbackService.ChatResult ok = service.chat("9.9.9.9", "conv-2", "질문 " + i, List.of());
            assertThat(ok.rateLimited()).isFalse();
        }

        ChatFeedbackService.ChatResult blocked = service.chat("9.9.9.9", "conv-2", "한 번 더", List.of());

        assertThat(blocked.rateLimited()).isTrue();
        verify(llmProvider, times(3)).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("LLM 프로바이더 미설정이면 안내 메시지를 반환하고 호출하지 않는다")
    void unconfigured_provider_returns_placeholder() {
        when(llmProvider.isConfigured()).thenReturn(false);

        ChatFeedbackService.ChatResult result = service.chat("1.1.1.1", "conv-3", "안녕하세요", List.of());

        assertThat(result.reply()).contains("LLM 프로바이더 미설정");
        verify(llmProvider, never()).chat(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("정상 응답은 JSON에서 reply를 파싱하고 피드백 로그로 저장한다")
    void parses_reply_and_saves_feedback_log() {
        when(llmProvider.isConfigured()).thenReturn(true);
        when(llmProvider.chat(anyString(), anyString(), anyInt()))
                .thenReturn("```json\n{\"reply\": \"이 서비스는 참고용 지표를 보여줍니다.\"}\n```");

        ChatFeedbackService.ChatResult result = service.chat("2.2.2.2", "conv-4", "이 서비스 뭐하는 곳이야?", List.of());

        assertThat(result.reply()).isEqualTo("이 서비스는 참고용 지표를 보여줍니다.");

        ArgumentCaptor<FeedbackLog> captor = ArgumentCaptor.forClass(FeedbackLog.class);
        verify(feedbackLogRepository).save(captor.capture());
        assertThat(captor.getValue().getUserMessage()).isEqualTo("이 서비스 뭐하는 곳이야?");
        assertThat(captor.getValue().getAssistantReply()).isEqualTo("이 서비스는 참고용 지표를 보여줍니다.");
    }
}
