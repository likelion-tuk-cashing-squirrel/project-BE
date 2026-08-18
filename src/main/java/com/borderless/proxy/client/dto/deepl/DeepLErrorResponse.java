package com.borderless.proxy.client.dto.deepl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * DeepL 에러 바디
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepLErrorResponse {

    private final String message;
    private final String detail;

    @Builder
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public DeepLErrorResponse(@JsonProperty("message") String message,
                              @JsonProperty("detail") String detail) {
        this.message = message;
        this.detail = detail;
    }

    public String getMessageOrDefault() {
        return (message == null || message.isBlank()) ? "알 수 없는 DeepL 오류" : message;
    }
}
