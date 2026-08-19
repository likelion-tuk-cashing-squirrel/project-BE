package com.borderless.proxy.client.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmRequestTest {

    @Test
    @DisplayName("userPrompt만으로 생성하면 나머지는 null이다 (기본값은 LlmClient가 채운다)")
    void createWithUserPromptOnly() {
        LlmRequest request = LlmRequest.of("번역해줘");

        assertThat(request.getUserPrompt()).isEqualTo("번역해줘");
        assertThat(request.getSystemPrompt()).isNull();
        assertThat(request.getModel()).isNull();
        assertThat(request.getTemperature()).isNull();
        assertThat(request.getMaxTokens()).isNull();
    }

    @Test
    @DisplayName("systemPrompt와 함께 생성할 수 있다")
    void createWithSystemPrompt() {
        LlmRequest request = LlmRequest.of("너는 번역가야", "안녕하세요");

        assertThat(request.getSystemPrompt()).isEqualTo("너는 번역가야");
        assertThat(request.getUserPrompt()).isEqualTo("안녕하세요");
    }

    @Test
    @DisplayName("userPrompt가 null이면 예외가 발생한다")
    void nullUserPrompt() {
        assertThatThrownBy(() -> LlmRequest.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPrompt");
    }

    @Test
    @DisplayName("userPrompt가 공백뿐이면 예외가 발생한다 - 네트워크로 나가기 전에 막는다")
    void blankUserPrompt() {
        assertThatThrownBy(() -> LlmRequest.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPrompt");
    }

    @Test
    @DisplayName("빌더로 모든 값을 지정할 수 있다")
    void builder() {
        LlmRequest request = LlmRequest.builder()
                .systemPrompt("system")
                .userPrompt("user")
                .model("gpt-4.1-mini")
                .temperature(0.7)
                .maxTokens(500)
                .build();

        assertThat(request.getModel()).isEqualTo("gpt-4.1-mini");
        assertThat(request.getTemperature()).isEqualTo(0.7);
        assertThat(request.getMaxTokens()).isEqualTo(500);
    }
}
