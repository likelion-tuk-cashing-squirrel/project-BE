package com.borderless.proxy.billing;

import com.borderless.proxy.billing.config.BillingProperties;
import com.borderless.proxy.billing.config.ModelPricing;
import com.borderless.proxy.billing.dto.CostBreakdown;
import com.borderless.proxy.billing.dto.ReportedUsage;
import com.borderless.proxy.billing.dto.TokenComparison;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 토큰 수를 USD 비용으로 환산하고 절약분 수수료를 계산한다.
 *
 * <p><b>두 종류의 토큰 수를 서로 다른 목적에 쓴다.</b>
 * <ul>
 *   <li>원가: API가 보고한 {@link ReportedUsage}. 시스템 프롬프트 오버헤드와 추론 토큰이 포함된
 *       청구 근거값이라, 우리가 로컬에서 다시 세면 실제 청구액과 어긋난다</li>
 *   <li>절감분: {@link TokenComparison}. 같은 토크나이저로 페이로드 텍스트만 센 전후 비교값이라
 *       차액을 구할 때 오버헤드가 양쪽에서 동일하게 상쇄된다</li>
 * </ul>
 *
 * <p>기준 비용은 원가에 회피 금액을 더해서 구한다. 기준값을 따로 환산해 실제값에서 빼면
 * 한쪽에만 프롬프트 오버헤드가 들어가 차액이 그만큼 틀어진다.
 *
 * <p>입력과 출력에 각각의 단가를 적용한다. 출력 단가가 입력의 4배 수준이라 토큰 수를 먼저 합산한 뒤
 * 하나의 단가를 곱하면 금액이 크게 틀어진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostCalculator {

    /** {@code UsageLog.costUsd} 컬럼의 scale과 맞춘다. 저장 시 잘려서 값이 달라지는 걸 막는다. */
    private static final int USD_SCALE = 6;

    /** 단가 기준 단위. 공급자 가격표가 100만 토큰당으로 공시된다. */
    private static final BigDecimal PRICING_UNIT = BigDecimal.valueOf(1_000_000);

    private static final BigDecimal ZERO_USD = BigDecimal.ZERO.setScale(USD_SCALE, RoundingMode.UNNECESSARY);

    private final BillingProperties billingProperties;

    /**
     * 요청 하나의 비용 내역을 계산한다.
     *
     * @param modelName  호출한 모델 이름. {@code billing.models}에 단가가 등록돼 있어야 한다
     * @param usage      API가 보고한 실측 사용량. 원가의 근거
     * @param comparison 입력·출력 토큰 전후 비교. 절감분의 근거
     * @throws IllegalArgumentException 모델 단가가 없거나 인자가 {@code null}일 때
     */
    public CostBreakdown calculate(String modelName, ReportedUsage usage, TokenComparison comparison) {
        if (usage == null) {
            throw new IllegalArgumentException("usage는 null일 수 없습니다.");
        }
        if (comparison == null) {
            throw new IllegalArgumentException("comparison은 null일 수 없습니다.");
        }

        ModelPricing pricing = billingProperties.pricingFor(modelName);

        BigDecimal actualCost = cost(usage.inputTokens(), pricing.inputPerMillion())
                .add(cost(usage.outputTokens(), pricing.outputPerMillion()));

        // 입력·출력을 각각의 단가로 환산해 합산한다. 절감 토큰이 음수인 구간은 그만큼 차감된다.
        BigDecimal savedCost = cost(comparison.savedInputTokens(), pricing.inputPerMillion())
                .add(cost(comparison.savedOutputTokens(), pricing.outputPerMillion()));

        BigDecimal baselineCost = actualCost.add(savedCost);
        BigDecimal fee = feeOn(savedCost);

        log.debug("비용 계산 완료: model={}, savedInput={}, savedOutput={}, actualUsd={}, savedUsd={}, feeUsd={}",
                modelName, comparison.savedInputTokens(), comparison.savedOutputTokens(),
                actualCost, savedCost, fee);

        return new CostBreakdown(modelName, comparison, actualCost, savedCost, baselineCost, fee);
    }

    /**
     * 절약분에 수수료율을 곱한다.
     *
     * <p>절약분이 0 이하면 0을 반환한다. 마스킹이나 피벗이 역효과를 낸 요청에서 음수 수수료가 나오면
     * 정산 합계가 조용히 깎여서 원인을 찾기 어렵다. 손해는 {@code savedCostUsd}에 음수로 남으니
     * 지표에서는 여전히 보인다.
     */
    private BigDecimal feeOn(BigDecimal savedCost) {
        if (savedCost.signum() <= 0) {
            return ZERO_USD;
        }
        return savedCost.multiply(billingProperties.feeRate()).setScale(USD_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 토큰 수를 금액으로 환산한다. 절감 토큰이 음수일 수 있으므로 음수 입력을 허용한다.
     */
    private static BigDecimal cost(int tokens, BigDecimal ratePerMillion) {
        return ratePerMillion
                .multiply(BigDecimal.valueOf(tokens))
                .divide(PRICING_UNIT, USD_SCALE, RoundingMode.HALF_UP);
    }
}
