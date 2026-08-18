package com.borderless.proxy.client.dto.deepl;

import com.borderless.proxy.client.dto.TranslationResponse;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * DeepL 번역 응답 바디
 * { "translations": [ { "text": "...", "detected_source_language": "EN", "billed_characters": 42 } ] }
 * Jackson 3(Spring Boot 4) 호환을 위해 @Jacksonized 대신 @JsonCreator 생성자를 사용
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeepLTranslateResponse {

    private final List<DeepLTranslation> translations;

    @Builder
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public DeepLTranslateResponse(@JsonProperty("translations") List<DeepLTranslation> translations) {
        this.translations = translations;
    }

    /**
     * DeepL 응답 -> 내부 DTO 변환
     *
     * @param latencyMs TranslationClient가 측정한 왕복 시간(ms)
     */
    public TranslationResponse toTranslationResponse(int latencyMs) {
        List<TranslationResponse.Translation> result = (translations == null)
                ? List.of()
                : translations.stream()
                .map(t -> TranslationResponse.Translation.builder()
                        .text(t.getText())
                        .detectedSourceLang(t.getDetectedSourceLanguage())
                        .billedCharacters(t.getBilledCharacters())
                        .build())
                .toList();

        return TranslationResponse.builder()
                .translations(result)
                .latencyMs(latencyMs)
                .build();
    }

    public boolean isEmpty() {
        return translations == null || translations.isEmpty();
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeepLTranslation {

        private final String text;
        private final String detectedSourceLanguage;
        private final Integer billedCharacters;
        private final String modelTypeUsed;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public DeepLTranslation(@JsonProperty("text") String text,
                                @JsonProperty("detected_source_language") String detectedSourceLanguage,
                                @JsonProperty("billed_characters") Integer billedCharacters,
                                @JsonProperty("model_type_used") String modelTypeUsed) {
            this.text = text;
            this.detectedSourceLanguage = detectedSourceLanguage;
            this.billedCharacters = billedCharacters;
            this.modelTypeUsed = modelTypeUsed;
        }
    }
}
