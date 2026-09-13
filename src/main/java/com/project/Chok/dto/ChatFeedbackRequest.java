package com.project.Chok.dto;

import java.util.List;

/**
 * conversationId는 클라이언트가 대화 시작 시 생성해 매 요청마다 그대로 보내는 임의
 * 문자열 - 로그인이 없어 신원 식별용이 아니라 feedback_logs에서 같은 대화의 턴을
 * 묶어보기 위한 용도일 뿐이다. history는 서버가 대화 맥락을 이해하기 위해 매 요청마다
 * 클라이언트가 함께 보내는 이전 턴들(서버는 이를 영구 저장하지 않는다).
 */
public record ChatFeedbackRequest(String conversationId, String message, List<ChatMessage> history) {}
