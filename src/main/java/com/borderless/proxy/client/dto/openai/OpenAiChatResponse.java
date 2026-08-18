package com.borderless.proxy.client.dto.openai;

import com.borderless.proxy.client.dto.LlmResponse;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * OpenAI Chat Completions 응답 바디
 * ignoreUnknown = true : OpenAI가 필드를 추가해도 파싱이 깨지지 않음
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiChatResponse {

    private final String id;
    private final String model;
    private final List<Choice> choices;
    private final Usage usage;

    @Builder
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public OpenAiChatResponse(@JsonProperty("id") String id,
                              @JsonProperty("model") String model,
                              @JsonProperty("choices") List<Choice> choices,
                              @JsonProperty("usage") Usage usage) {
        this.id = id;
        this.model = model;
        this.choices = choices;
        this.usage = usage;
    }

    /**
     * OpenAI 응답 -> 내부 DTO 변환
     *
     * @param latencyMs LlmClient가 측정한 왕복 시간(ms)
     */
    public LlmResponse toLlmResponse(int latencyMs) {
        Choice first = (choices == null || choices.isEmpty()) ? null : choices.get(0);

        return LlmResponse.builder()
                .content(first == null || first.getMessage() == null ? null : first.getMessage().getContent())
                .model(model)
                .finishReason(first == null ? null : first.getFinishReason())
                .usage(usage == null ? LlmResponse.TokenUsage.empty() : usage.toTokenUsage())
                .latencyMs(latencyMs)
                .build();
    }

    /** 응답 본문이 비어 있는 비정상 응답인지 판별 */
    public boolean isEmpty() {
        return choices == null
                || choices.isEmpty()
                || choices.get(0).getMessage() == null
                || choices.get(0).getMessage().getContent() == null;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {

        private final int index;
        private final Message message;
        private final String finishReason;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public Choice(@JsonProperty("index") int index,
                      @JsonProperty("message") Message message,
                      @JsonProperty("finish_reason") String finishReason) {
            this.index = index;
            this.message = message;
            this.finishReason = finishReason;
        }
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {

        private final String role;
        private final String content;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public Message(@JsonProperty("role") String role,
                       @JsonProperty("content") String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Usage {

        private final int promptTokens;
        private final int completionTokens;
        private final int totalTokens;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public Usage(@JsonProperty("prompt_tokens") int promptTokens,
                     @JsonProperty("completion_tokens") int completionTokens,
                     @JsonProperty("total_tokens") int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }

        public LlmResponse.TokenUsage toTokenUsage() {
            return LlmResponse.TokenUsage.builder()
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(totalTokens)
                    .build();
        }
    }
}
