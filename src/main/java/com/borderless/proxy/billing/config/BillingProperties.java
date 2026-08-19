package com.borderless.proxy.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 과금 설정. 모델별 토큰 단가와 수수료율을 담는다.
 *
 * <p>단가는 공급자가 예고 없이 바꾸고 호출 모델도 교체될 수 있어서 코드에 박지 않는다.
 * 수수료율 역시 정책값이라 배포 없이 조정할 수 있어야 한다.
 *
 * @param feeRate 절약분에 곱해 수수료를 구하는 비율. 0.10이면 절약한 금액의 10%를 수수료로 받는다.
 *                원가에 얹는 마크업이 아니라 <b>절약분에만</b> 적용되는 성과 수수료다.
 * @param models  모델 이름 → 단가. 키는 {@code UsageLog.modelName}에 저장할 이름과 같게 맞춘다.
 */
@ConfigurationProperties(prefix = "billing")
public record BillingProperties(

        @DefaultValue("0.10") BigDecimal feeRate,

        Map<String, ModelPricing> models
) {

    public BillingProperties {
        if (feeRate == null) {
            throw new IllegalArgumentException("billing.fee-rate는 null일 수 없습니다.");
        }
        if (feeRate.signum() < 0 || feeRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("billing.fee-rate는 0.0 ~ 1.0 범위여야 합니다: " + feeRate);
        }
        // 설정 파일에 models 블록이 아예 없어도 애플리케이션은 떠야 한다.
        // 단가가 필요한 시점에 pricingFor가 명확한 메시지로 알려주는 편이, 부팅 실패보다 원인 파악이 쉽다.
        models = normalize(models);
    }

    /**
     * 모델 이름 끝에 붙는 스냅샷 날짜. 예: {@code gpt-4.1-mini-2025-04-14}의 {@code -2025-04-14}
     *
     * <p>OpenAI는 별칭({@code gpt-4.1-mini})으로 요청해도 응답 {@code model} 필드에는
     * 스냅샷 이름을 돌려준다. 설정에 별칭만 등록해도 조회가 되도록 이 부분을 떼고 재시도한다.
     */
    private static final Pattern SNAPSHOT_SUFFIX = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    /**
     * 모델 이름으로 단가를 찾는다.
     *
     * <p>대소문자를 구분하지 않는다. 모델 이름은 LLM 클라이언트가 응답에서 그대로 넘겨주는 값이라
     * {@code GPT-4o-mini}처럼 표기가 흔들릴 수 있는데, 그때마다 단가를 못 찾아 요청이 실패하면 손해다.
     *
     * <p>정확히 일치하는 키가 없으면 스냅샷 날짜를 뗀 별칭으로 한 번 더 찾는다.
     * 그래도 없으면 예외를 던진다. 기본 단가로 조용히 폴백하지 않는 이유는,
     * 틀린 금액이 로그 없이 정산에 쌓이는 쪽이 요청 하나 실패하는 것보다 나쁘기 때문이다.
     *
     * @param modelName 찾을 모델 이름
     * @throws IllegalArgumentException 설정에 해당 모델 단가가 없을 때. 등록된 이름을 메시지에 함께 싣는다.
     */
    public ModelPricing pricingFor(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName은 null이거나 공백일 수 없습니다.");
        }

        String key = key(modelName);
        ModelPricing pricing = models.get(key);

        if (pricing == null) {
            String alias = SNAPSHOT_SUFFIX.matcher(key).replaceFirst("");
            if (!alias.equals(key)) {
                pricing = models.get(alias);
            }
        }

        if (pricing == null) {
            throw new IllegalArgumentException(
                    "billing.models에 '%s' 단가가 없습니다. 등록된 모델: %s".formatted(modelName, models.keySet()));
        }
        return pricing;
    }

    private static Map<String, ModelPricing> normalize(Map<String, ModelPricing> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }

        // 대소문자 무시 조회를 위해 키를 정규화한다. 정렬 맵을 쓰는 이유는 예외 메시지에
        // 등록된 모델 목록이 매번 같은 순서로 찍혀야 로그를 비교하기 쉽기 때문이다.
        Map<String, ModelPricing> normalized = new TreeMap<>();
        source.forEach((name, pricing) -> normalized.put(key(name), pricing));
        return Map.copyOf(normalized);
    }

    private static String key(String modelName) {
        return modelName.trim().toLowerCase(Locale.ROOT);
    }
}
