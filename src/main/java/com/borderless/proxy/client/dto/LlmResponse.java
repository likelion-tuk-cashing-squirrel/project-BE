package com.borderless.proxy.client.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * LlmClient가 돌려주는 벤더 중립 응답 DTO
 * UsageLog 엔티티(inputTokens / outputTokens / latencyMs / modelName)를
 * 오케스트레이터가 그대로 채울 수 있도록 필요한 값을 모두 포함
 */
@Getter
public class LlmResponse {

    /** LLM이 생성한 본문 (마스킹 토큰이 살아있는 상태) */
    private final String content;

    /** 실제 사용된 모델명 -> UsageLog.modelName */
    private final String model;

    /** 토큰 사용량 -> UsageLog.inputTokens / outputTokens */
    private final TokenUsage usage;

    /** "stop" | "length" | "content_filter" 등 */
    private final String finishReason;

    /** 외부 API 호출 왕복 시간(ms) -> usage_log.latency_ms (INT) */
    private final int latencyMs;

    @Builder
    public LlmResponse(String content, String model, TokenUsage usage, String finishReason, int latencyMs) {
        this.content = content;
        this.model = model;
        this.usage = usage == null ? TokenUsage.empty() : usage;
        this.finishReason = finishReason;
        this.latencyMs = latencyMs;
    }

    /** 응답이 길이 제한으로 잘렸는지 여부 */
    public boolean isTruncated() {
        return "length".equals(finishReason);
    }

    @Getter
    public static class TokenUsage {

        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        @Builder
        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public static TokenUsage empty() {
            return new TokenUsage(0, 0, 0);
        }
    }
}
