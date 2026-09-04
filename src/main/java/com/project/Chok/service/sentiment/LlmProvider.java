package com.project.Chok.service.sentiment;

/**
 * 감성 분석에 사용하는 LLM 채팅 API를 프로바이더별로 추상화한다.
 * 프롬프트 작성과 응답 JSON({score,label,summary}) 파싱은 SentimentAnalysisService에서
 * 공통으로 처리하고, 여기서는 각 프로바이더의 요청 형식/인증/응답 envelope 차이만 감춘다.
 */
public interface LlmProvider {

    /** API 키 등 필수 설정이 되어 있는지 여부. */
    boolean isConfigured();

    /**
     * 시스템 프롬프트 + 사용자 메시지를 전송하고 LLM이 응답한 원문 텍스트를 그대로 반환한다.
     * maxTokens는 안전장치용 상한이며(호출자가 배치 크기에 맞춰 계산해서 넘김), 실제 과금은
     * 모델이 실제로 생성한 토큰 수 기준이라 이 값 자체가 비용을 줄여주지는 않는다.
     * 네트워크 오류나 응답 envelope 파싱 실패 시 예외를 던진다 (호출측에서 중립 폴백 처리).
     */
    String chat(String systemPrompt, String userMessage, int maxTokens);
}
