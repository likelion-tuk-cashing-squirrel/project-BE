package com.borderless.proxy.client.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Objects;

/**
 * TranslationClient가 돌려주는 벤더 중립 번역 응답 DTO
 * <p>
 * translations는 요청 texts와 순서/개수가 1:1로 대응
 */
@Getter
public class TranslationResponse {

    private final List<Translation> translations;

    /** 외부 API 호출 왕복 시간(ms) -> usage_log.latency_ms (INT) */
    private final int latencyMs;

    @Builder
    public TranslationResponse(List<Translation> translations, int latencyMs) {
        this.translations = translations == null ? List.of() : List.copyOf(translations);
        this.latencyMs = latencyMs;
    }

    /** 단건 호출 시 편의 메서드 */
    public String getFirstText() {
        return translations.isEmpty() ? null : translations.get(0).getText();
    }

    /** 번역문만 순서대로 추출 */
    public List<String> getTexts() {
        return translations.stream().map(Translation::getText).toList();
    }

    /** 과금 문자 수 합계 -> UsageLog 산정용 */
    public int getTotalBilledCharacters() {
        return translations.stream()
                .map(Translation::getBilledCharacters)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    @Getter
    public static class Translation {

        /** 번역된 텍스트 (마스킹 토큰이 살아있는 상태) */
        private final String text;

        /** DeepL이 자동 감지한 출발 언어 */
        private final String detectedSourceLang;

        /** 과금 문자 수. show_billed_characters=true일 때만 채워짐 */
        private final Integer billedCharacters;

        @Builder
        public Translation(String text, String detectedSourceLang, Integer billedCharacters) {
            this.text = text;
            this.detectedSourceLang = detectedSourceLang;
            this.billedCharacters = billedCharacters;
        }
    }
}
