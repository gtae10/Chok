package com.project.Chok.dto;

/** 채팅 위젯의 대화 이력 한 턴. role은 "user" | "assistant". */
public record ChatMessage(String role, String content) {}
