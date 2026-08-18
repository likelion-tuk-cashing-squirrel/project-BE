package com.borderless.proxy.routing.dto;

/**
 * 언어 감지 결과.
 *
 * @param languageCode 감지된 언어의 ISO 639-1 코드. 특정하지 못하면 {@link RoutingResult#UNDETERMINED_LANGUAGE}
 * @param confidence   감지 신뢰도 (0.0 ~ 1.0). 특정하지 못하면 0.0
 */
public record LanguageDetection(String languageCode, double confidence) {

    private static final LanguageDetection UNDETERMINED =
            new LanguageDetection(RoutingResult.UNDETERMINED_LANGUAGE, 0.0);

    public LanguageDetection {
        if (languageCode == null || languageCode.isBlank()) {
            languageCode = RoutingResult.UNDETERMINED_LANGUAGE;
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence는 0.0 ~ 1.0 범위여야 합니다: " + confidence);
        }
    }

    /** 언어를 특정할 수 없을 때의 결과. */
    public static LanguageDetection undetermined() {
        return UNDETERMINED;
    }

    /** 언어를 특정하지 못했는지 여부. */
    public boolean isUndetermined() {
        return RoutingResult.UNDETERMINED_LANGUAGE.equals(languageCode);
    }
}
