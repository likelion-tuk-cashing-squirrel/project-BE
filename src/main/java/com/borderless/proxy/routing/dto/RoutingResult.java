package com.borderless.proxy.routing.dto;

import com.borderless.proxy.routing.RoutingTier;

/**
 * 라우터(STEP 01)의 판정 결과. 오케스트레이터는 이 값을 보고 이후 단계를 분기시킨다.
 *
 * @param tier            결정된 처리 경로
 * @param detectedLanguage 감지된 언어의 ISO 639-1 코드. 감지를 생략했거나 특정하지 못하면 {@link #UNDETERMINED_LANGUAGE}
 * @param estimatedTokens JTokkit o200k_base 기준 원문 토큰 수
 * @param confidence      언어 감지 신뢰도 (0.0 ~ 1.0). 감지를 생략했으면 0.0
 */
public record RoutingResult(
        RoutingTier tier,
        String detectedLanguage,
        int estimatedTokens,
        double confidence
) {

    /**
     * 언어를 특정하지 못했음을 나타내는 코드.
     * {@code null} 대신 ISO 639-2의 "und"(undetermined)를 사용해 호출부의 널 체크 부담을 없앤다.
     */
    public static final String UNDETERMINED_LANGUAGE = "und";

    public RoutingResult {
        if (tier == null) {
            throw new IllegalArgumentException("tier는 null일 수 없습니다.");
        }
        if (detectedLanguage == null || detectedLanguage.isBlank()) {
            detectedLanguage = UNDETERMINED_LANGUAGE;
        }
        if (estimatedTokens < 0) {
            throw new IllegalArgumentException("estimatedTokens는 음수일 수 없습니다: " + estimatedTokens);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence는 0.0 ~ 1.0 범위여야 합니다: " + confidence);
        }
    }

    /**
     * 임계치 미만이라 언어 감지 없이 통과시키는 결과.
     *
     * @param estimatedTokens 계산된 원문 토큰 수
     */
    public static RoutingResult skip(int estimatedTokens) {
        return new RoutingResult(RoutingTier.SKIP, UNDETERMINED_LANGUAGE, estimatedTokens, 0.0);
    }
}
