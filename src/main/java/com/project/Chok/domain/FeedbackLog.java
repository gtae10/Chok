package com.project.Chok.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 피드백 챗봇과 나눈 대화 원문을 개발자가 나중에 검토할 수 있도록 저장한다.
 * 실시간 응답 자체는 서버에 대화 이력을 유지하지 않지만(클라이언트 JS 메모리에서만
 * 유지), 사용자가 남긴 피드백 내용은 별도로 남긴다. 로그인이 없어 사용자를 특정할
 * 방법이 없고 개인정보도 받지 않으므로, conversationId는 신원과 무관하게 클라이언트가
 * 대화 시작 시 생성한 임의 문자열(같은 대화의 턴을 묶는 용도)일 뿐이다.
 */
@Entity
@Table(name = "feedback_logs")
public class FeedbackLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", length = 64)
    private String conversationId;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "assistant_reply", columnDefinition = "TEXT")
    private String assistantReply;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getUserMessage() { return userMessage; }
    public void setUserMessage(String userMessage) { this.userMessage = userMessage; }

    public String getAssistantReply() { return assistantReply; }
    public void setAssistantReply(String assistantReply) { this.assistantReply = assistantReply; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
