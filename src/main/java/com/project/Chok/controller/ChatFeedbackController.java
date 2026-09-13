package com.project.Chok.controller;

import com.project.Chok.dto.ChatFeedbackRequest;
import com.project.Chok.dto.ChatFeedbackResponse;
import com.project.Chok.service.ChatFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatFeedbackController {

    private final ChatFeedbackService chatFeedbackService;

    public ChatFeedbackController(ChatFeedbackService chatFeedbackService) {
        this.chatFeedbackService = chatFeedbackService;
    }

    @PostMapping("/feedback")
    public ResponseEntity<ChatFeedbackResponse> feedback(@RequestBody ChatFeedbackRequest request,
                                                          HttpServletRequest httpRequest) {
        ChatFeedbackService.ChatResult result = chatFeedbackService.chat(
                clientIp(httpRequest), request.conversationId(), request.message(), request.history());
        return ResponseEntity.ok(new ChatFeedbackResponse(result.reply()));
    }

    /** 배포용 리버스프록시(Dockerfile/docker-compose 구성) 뒤에서도 원 클라이언트 IP를 쓰기 위함. */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
