package com.borderless.proxy.client.dto.openai;

import com.borderless.proxy.client.dto.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI로 실제 전송되는 JSON 모양 검증
 * Jackson 3(tools.jackson) 환경이지만 어노테이션 패키지는 com.fasterxml.jackson.annotation 그대로
 */
class OpenAiChatRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("systemPrompt가 있으면 messages가 system, user 순서로 2개 생성된다")
    void withSystemPrompt() {
        LlmRequest source = LlmRequest.of("너는 번역가야", "안녕하세요");

        OpenAiChatRequest request = OpenAiChatRequest.from(source, "gpt-4.1-mini", 0.3, 2048);

        assertThat(request.getMessages()).hasSize(2);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(request.getMessages().get(0).getContent()).isEqualTo("너는 번역가야");
        assertThat(request.getMessages().get(1).getRole()).isEqualTo("user");
        assertThat(request.getMessages().get(1).getContent()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("systemPrompt가 없으면 messages는 user 하나뿐이다")
    void withoutSystemPrompt() {
        OpenAiChatRequest request =
                OpenAiChatRequest.from(LlmRequest.of("안녕하세요"), "gpt-4.1-mini", 0.3, 2048);

        assertThat(request.getMessages()).hasSize(1);
        assertThat(request.getMessages().get(0).getRole()).isEqualTo("user");
    }

    @Test
    @DisplayName("systemPrompt가 공백뿐이면 message로 추가되지 않는다")
    void blankSystemPromptIsSkipped() {
        LlmRequest source = LlmRequest.builder()
                .systemPrompt("   ")
                .userPrompt("안녕하세요")
                .build();

        OpenAiChatRequest request = OpenAiChatRequest.from(source, "gpt-4.1-mini", 0.3, 2048);

        assertThat(request.getMessages()).hasSize(1);
    }

    @Test
    @DisplayName("직렬화하면 OpenAI 스펙대로 스네이크 케이스 필드명이 나간다")
    void serializesToSnakeCase() {
        OpenAiChatRequest request =
                OpenAiChatRequest.from(LlmRequest.of("안녕하세요"), "gpt-4.1-mini", 0.3, 2048);

        String json = mapper.writeValueAsString(request);

        assertThat(json).contains("\"model\":\"gpt-4.1-mini\"");
        assertThat(json).contains("\"max_completion_tokens\":2048");
        assertThat(json).contains("\"temperature\":0.3");
        assertThat(json).contains("\"stream\":false");
        assertThat(json).contains("\"role\":\"user\"");
        // camelCase가 새어나가면 OpenAI가 인식하지 못함
        assertThat(json).doesNotContain("maxCompletionTokens");
    }

    @Test
    @DisplayName("null 필드는 아예 전송되지 않는다 (@JsonInclude NON_NULL)")
    void nullFieldsAreOmitted() {
        OpenAiChatRequest request =
                OpenAiChatRequest.from(LlmRequest.of("안녕하세요"), "gpt-4.1-mini", null, null);

        String json = mapper.writeValueAsString(request);

        assertThat(json).doesNotContain("temperature");
        assertThat(json).doesNotContain("max_completion_tokens");
        assertThat(json).doesNotContain("null");
    }
}
