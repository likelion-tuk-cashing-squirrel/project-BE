package com.borderless.proxy.routing;

import java.util.Locale;
import java.util.Map;

/**
 * 라우터가 결정하는 요청 처리 경로.
 *
 * <p>boolean(isPivot) 대신 enum을 쓰는 이유는 분기가 4가지이며,
 * {@link #TIER_3}는 형태소 분석 마이크로서비스를 추가로 호출해야 하므로 구분이 필요하기 때문이다.
 *
 * <p>각 티어는 뒤쪽 파이프라인이 어떤 단계를 수행해야 하는지를 플래그로 노출한다.
 * 라우터 자체는 텍스트를 변형하지 않고 판단만 한다.
 */
public enum RoutingTier {

    /** 토큰 수가 임계치 미만. 마스킹/피벗 전부 생략하고 바로 LLM을 호출한다. */
    SKIP(false, false, false),

    /** 영어. 용어집 마스킹은 하되 영어 피벗/재번역은 불필요하다. */
    TIER_1(true, false, false),

    /** 베트남어 등. 영어 피벗 + 재번역이 필요하다. */
    TIER_2(true, true, false),

    /** 필리핀어(타갈로그). 형태소 힌트 주입 + 피벗 + 재번역이 필요하다. */
    TIER_3(true, true, true);

    /**
     * ISO 639-1 언어 코드 → 티어 매핑.
     * 여기에 없는 언어는 가장 안전한 폴백인 {@link #TIER_1}로 처리한다.
     */
    private static final Map<String, RoutingTier> TIER_BY_LANGUAGE = Map.of(
            "en", TIER_1,
            "vi", TIER_2,
            "tl", TIER_3
    );

    /** 언어를 특정할 수 없을 때 사용하는 폴백 티어. 영어로 취급하는 것이 가장 안전하다. */
    public static final RoutingTier FALLBACK = TIER_1;

    private final boolean requiresMasking;
    private final boolean requiresPivot;
    private final boolean requiresMorphologyHint;

    RoutingTier(boolean requiresMasking, boolean requiresPivot, boolean requiresMorphologyHint) {
        this.requiresMasking = requiresMasking;
        this.requiresPivot = requiresPivot;
        this.requiresMorphologyHint = requiresMorphologyHint;
    }

    /**
     * 감지된 언어 코드에 대응하는 티어를 반환한다.
     *
     * @param languageCode ISO 639-1 언어 코드. {@code null}이거나 매핑에 없으면 {@link #FALLBACK}
     */
    public static RoutingTier fromLanguageCode(String languageCode) {
        if (languageCode == null || languageCode.isBlank()) {
            return FALLBACK;
        }
        return TIER_BY_LANGUAGE.getOrDefault(languageCode.toLowerCase(Locale.ROOT), FALLBACK);
    }

    /** 용어집 마스킹 단계(STEP 02) 수행 여부. */
    public boolean requiresMasking() {
        return requiresMasking;
    }

    /** 영어 피벗 단계(STEP 03) 수행 여부. */
    public boolean requiresPivot() {
        return requiresPivot;
    }

    /** 형태소 힌트 주입(STEP 03-B) 수행 여부. TIER_3만 해당한다. */
    public boolean requiresMorphologyHint() {
        return requiresMorphologyHint;
    }

    /** 원어 재번역 단계(STEP 05) 수행 여부. 피벗한 경우에만 되돌릴 대상이 생긴다. */
    public boolean requiresRetranslation() {
        return requiresPivot;
    }
}
