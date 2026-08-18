package com.borderless.proxy.billing.dto;

import java.math.BigDecimal;

/**
 * 요청 하나의 비용 내역. 대시보드 집계와 {@code UsageLog} 저장에 그대로 쓸 수 있다.
 *
 * <p>금액은 모두 USD이며 소수점 6자리로 반올림돼 있다. {@code UsageLog.costUsd} 컬럼이
 * {@code precision = 10, scale = 6}이라 그보다 정밀한 값을 만들어 두면 저장 시점에 잘려서
 * 계산기가 반환한 값과 DB에 남은 값이 달라진다.
 *
 * @param modelName       비용을 계산한 모델 이름
 * @param comparison      절감분 산정 근거. 입력·출력 토큰 비교값
 * @param actualCostUsd   API 보고 사용량으로 계산한 실제 원가. 추론 토큰과 프롬프트 오버헤드가 포함된다
 * @param savedCostUsd    프록시로 회피한 금액. 입력·출력에 각각의 단가를 적용해 합산한다.
 *                        역효과가 났으면 음수
 * @param baselineCostUsd {@code actualCostUsd + savedCostUsd}. 프록시를 안 썼다면 나왔을 청구액
 * @param feeUsd          절약분에 수수료율을 곱한 금액. 절약분이 0 이하면 0
 */
public record CostBreakdown(
        String modelName,
        TokenComparison comparison,
        BigDecimal actualCostUsd,
        BigDecimal savedCostUsd,
        BigDecimal baselineCostUsd,
        BigDecimal feeUsd
) {

    public CostBreakdown {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName은 null이거나 공백일 수 없습니다.");
        }
        if (comparison == null) {
            throw new IllegalArgumentException("comparison은 null일 수 없습니다.");
        }
        requireNonNull(actualCostUsd, "actualCostUsd");
        requireNonNull(savedCostUsd, "savedCostUsd");
        requireNonNull(baselineCostUsd, "baselineCostUsd");
        requireNonNull(feeUsd, "feeUsd");

        // 원가와 수수료는 정의상 음수가 될 수 없다. savedCostUsd와 baselineCostUsd는
        // 파이프라인이 역효과를 냈을 때 음수가 나올 수 있으므로 부호를 검사하지 않는다.
        if (actualCostUsd.signum() < 0) {
            throw new IllegalArgumentException("actualCostUsd는 음수일 수 없습니다: " + actualCostUsd);
        }
        if (feeUsd.signum() < 0) {
            throw new IllegalArgumentException("feeUsd는 음수일 수 없습니다: " + feeUsd);
        }
    }

    /** 절감한 총 토큰 수. {@code UsageLog.savedTokens}에 저장할 값이다. */
    public int savedTokens() {
        return comparison.savedTokens();
    }

    /**
     * 사용자에게 청구할 총액. 실제로 든 원가에 절약분 수수료를 더한 값이다.
     *
     * <p>절약분이 음수여도 {@code feeUsd}가 0이라 원가만 청구된다.
     */
    public BigDecimal billedUsd() {
        return actualCostUsd.add(feeUsd);
    }

    private static void requireNonNull(BigDecimal value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + "은 null일 수 없습니다.");
        }
    }
}
