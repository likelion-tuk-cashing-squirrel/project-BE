package com.borderless.proxy.client.dto.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("OpenAI 에러 바디에서 메시지와 코드를 꺼낸다")
    void parseError() {
        String json = """
                {
                  "error": {
                    "message": "Incorrect API key provided.",
                    "type": "invalid_request_error",
                    "param": null,
                    "code": "invalid_api_key"
                  }
                }
                """;

        OpenAiErrorResponse error = mapper.readValue(json, OpenAiErrorResponse.class);

        assertThat(error.getMessageOrDefault()).isEqualTo("Incorrect API key provided.");
        assertThat(error.getCodeOrDefault()).isEqualTo("invalid_api_key");
        assertThat(error.getError().getType()).isEqualTo("invalid_request_error");
    }

    @Test
    @DisplayName("예상과 다른 에러 바디여도 기본 메시지를 돌려준다")
    void fallbackMessage() {
        OpenAiErrorResponse error = mapper.readValue("{}", OpenAiErrorResponse.class);

        assertThat(error.getMessageOrDefault()).isEqualTo("알 수 없는 OpenAI 오류");
        assertThat(error.getCodeOrDefault()).isEqualTo("unknown");
    }
}
