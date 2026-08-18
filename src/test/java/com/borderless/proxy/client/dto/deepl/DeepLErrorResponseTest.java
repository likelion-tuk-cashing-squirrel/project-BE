package com.borderless.proxy.client.dto.deepl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DeepLErrorResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("DeepL 에러 바디에서 메시지 추출")
    void parseError() {
        String json = """
                { "message": "Parameter 'target_lang' not specified." }
                """;

        DeepLErrorResponse error = mapper.readValue(json, DeepLErrorResponse.class);

        assertThat(error.getMessageOrDefault()).isEqualTo("Parameter 'target_lang' not specified.");
    }

    @Test
    @DisplayName("빈 바디여도 기본 메시지 반환(DeepL은 본문 없이 상태코드만 주기도 함)")
    void fallbackMessage() {
        assertThat(mapper.readValue("{}", DeepLErrorResponse.class).getMessageOrDefault())
                .isEqualTo("알 수 없는 DeepL 오류");
    }
}
