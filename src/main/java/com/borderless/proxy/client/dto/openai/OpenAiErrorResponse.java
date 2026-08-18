package com.borderless.proxy.client.dto.openai;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * OpenAI 에러 바디
 * <pre>
 * { "error": { "message": "...", "type": "...", "param": null, "code": "..." } }
 * </pre>
 * LlmClient의 onStatus 처리에서 역직렬화하여 예외 메시지로 사용
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiErrorResponse {

    private final Error error;

    @Builder
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public OpenAiErrorResponse(@JsonProperty("error") Error error) {
        this.error = error;
    }

    public String getMessageOrDefault() {
        return (error == null || error.getMessage() == null)
                ? "알 수 없는 OpenAI 오류"
                : error.getMessage();
    }

    public String getCodeOrDefault() {
        return (error == null || error.getCode() == null) ? "unknown" : error.getCode();
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Error {

        private final String message;
        private final String type;
        private final String param;
        private final String code;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public Error(@JsonProperty("message") String message,
                     @JsonProperty("type") String type,
                     @JsonProperty("param") String param,
                     @JsonProperty("code") String code) {
            this.message = message;
            this.type = type;
            this.param = param;
            this.code = code;
        }
    }
}
