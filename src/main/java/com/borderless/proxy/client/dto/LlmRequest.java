package com.borderless.proxy.client.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * LlmClient에 전달하는 벤더 중립 요청 DTO
  */
@Getter
public class LlmRequest {

    /** 시스템 프롬프트. nullable */
    private final String systemPrompt;

    /** 사용자 프롬프트. 필수 (이미 마스킹이 끝난 텍스트가 들어온다고 가정) */
    private final String userPrompt;

    /** 모델명. null이면 기본 모델 사용 */
    private final String model;

    /** 0.0 ~ 2.0. null이면 기본값 사용 */
    private final Double temperature;

    /** 최대 출력 토큰. null이면 기본값 사용 */
    private final Integer maxTokens;

    @Builder
    public LlmRequest(String systemPrompt, String userPrompt, String model, Double temperature, Integer maxTokens) {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("userPrompt는 비어 있을 수 없습니다.");
        }
        this.systemPrompt = systemPrompt;
        this.userPrompt = userPrompt;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    public static LlmRequest of(String userPrompt) {
        return LlmRequest.builder().userPrompt(userPrompt).build();
    }

    public static LlmRequest of(String systemPrompt, String userPrompt) {
        return LlmRequest.builder().systemPrompt(systemPrompt).userPrompt(userPrompt).build();
    }
}
