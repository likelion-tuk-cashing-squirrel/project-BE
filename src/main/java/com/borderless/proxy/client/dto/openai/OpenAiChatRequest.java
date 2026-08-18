package com.borderless.proxy.client.dto.openai;

import com.borderless.proxy.client.dto.LlmRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI Chat Completions 요청 바디
 * POST {base-url}/chat/completions
 * <p>
 * NON_NULL: null 필드는 직렬화에서 제외 (OpenAI가 null을 400으로 처리하는 경우 방지)
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiChatRequest {

    private final String model;

    private final List<Message> messages;

    private final Double temperature;

    @JsonProperty("max_completion_tokens")
    private final Integer maxCompletionTokens;

    /** 현재는 항상 false. 스트리밍 지원 시 확장 */
    private final Boolean stream;

    @Builder
    public OpenAiChatRequest(String model, List<Message> messages, Double temperature,
                             Integer maxCompletionTokens, Boolean stream) {
        this.model = model;
        this.messages = messages;
        this.temperature = temperature;
        this.maxCompletionTokens = maxCompletionTokens;
        this.stream = stream;
    }

    /**
     * 내부 DTO -> OpenAI 요청 변환
     * model / temperature / maxTokens는 LlmClient가 기본값을 확정한 뒤 전달
     */
    public static OpenAiChatRequest from(LlmRequest request, String model, Double temperature, Integer maxTokens) {
        List<Message> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isBlank()) {
            messages.add(Message.system(request.getSystemPrompt()));
        }
        messages.add(Message.user(request.getUserPrompt()));

        return OpenAiChatRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(temperature)
                .maxCompletionTokens(maxTokens)
                .stream(Boolean.FALSE)
                .build();
    }

    @Getter
    public static class Message {

        /** "system" | "user" | "assistant" */
        private final String role;

        private final String content;

        @Builder
        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }
    }
}
