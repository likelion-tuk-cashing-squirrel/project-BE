package com.borderless.proxy.client.dto.openai;

import com.borderless.proxy.client.dto.LlmResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lombok @Jacksonized 대신 @JsonCreator 생성자로 역직렬화가 되는지 검증
 * Spring Boot 4는 Jackson 3을 사용하므로 @Jacksonized 사용 불가
 */
class OpenAiChatResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 실제 OpenAI 응답 형태. object, created는 DTO에 없는 필드 */
    private static final String SAMPLE = """
            {
              "id": "chatcmpl-abc123",
              "object": "chat.completion",
              "created": 1728000000,
              "model": "gpt-4.1-mini-2025-04-14",
              "choices": [
                {
                  "index": 0,
                  "message": { "role": "assistant", "content": "번역 결과입니다" },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 120,
                "completion_tokens": 45,
                "total_tokens": 165
              }
            }
            """;

    @Test
    @DisplayName("스네이크 케이스 응답이 DTO로 파싱된다")
    void deserialize() {
        OpenAiChatResponse response = mapper.readValue(SAMPLE, OpenAiChatResponse.class);

        assertThat(response.getId()).isEqualTo("chatcmpl-abc123");
        assertThat(response.getModel()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getIndex()).isZero();
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getChoices().get(0).getMessage().getContent()).isEqualTo("번역 결과입니다");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(120);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(45);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(165);
    }

    @Test
    @DisplayName("DTO에 없는 필드(object, created)가 있어도 예외가 나지 않는다")
    void ignoresUnknownFields() {
        // SAMPLE에 object, created가 포함되어 있으므로 이 호출이 성공하면 ignoreUnknown이 동작하는 것
        assertThat(mapper.readValue(SAMPLE, OpenAiChatResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("OpenAI가 새 필드를 추가해도 파싱이 깨지지 않는다")
    void toleratesFutureFields() {
        String withNewField = """
                {
                  "id": "x",
                  "model": "gpt-4.1-mini",
                  "some_brand_new_field": { "nested": [1, 2, 3] },
                  "choices": [],
                  "usage": null
                }
                """;

        assertThat(mapper.readValue(withNewField, OpenAiChatResponse.class)).isNotNull();
    }

    @Test
    @DisplayName("내부 DTO로 변환하면 usage_log에 필요한 값이 모두 담긴다")
    void toLlmResponse() {
        LlmResponse llm = mapper.readValue(SAMPLE, OpenAiChatResponse.class).toLlmResponse(1234);

        assertThat(llm.getContent()).isEqualTo("번역 결과입니다");
        assertThat(llm.getModel()).isEqualTo("gpt-4.1-mini-2025-04-14");   // usage_log.model_name
        assertThat(llm.getUsage().getPromptTokens()).isEqualTo(120);       // usage_log.input_tokens
        assertThat(llm.getUsage().getCompletionTokens()).isEqualTo(45);    // usage_log.output_tokens
        assertThat(llm.getLatencyMs()).isEqualTo(1234);                    // usage_log.latency_ms
        assertThat(llm.getFinishReason()).isEqualTo("stop");
        assertThat(llm.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("finish_reason이 length면 응답이 잘린 것으로 판별한다")
    void truncatedResponse() {
        String truncated = SAMPLE.replace("\"stop\"", "\"length\"");

        LlmResponse llm = mapper.readValue(truncated, OpenAiChatResponse.class).toLlmResponse(10);

        assertThat(llm.isTruncated()).isTrue();
    }

    @Test
    @DisplayName("choices가 빈 비정상 응답에서도 NPE가 나지 않는다")
    void emptyChoices() {
        String json = """
                { "id": "x", "model": "gpt-4.1-mini", "choices": [], "usage": null }
                """;

        OpenAiChatResponse response = mapper.readValue(json, OpenAiChatResponse.class);

        assertThat(response.isEmpty()).isTrue();

        LlmResponse llm = response.toLlmResponse(10);
        assertThat(llm.getContent()).isNull();
        assertThat(llm.getUsage().getTotalTokens()).isZero();   // usage가 null이어도 0으로 채워진다
    }
}
