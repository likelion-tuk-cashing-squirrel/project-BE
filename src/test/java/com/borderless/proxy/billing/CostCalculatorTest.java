package com.borderless.proxy.billing;

import com.borderless.proxy.billing.config.BillingProperties;
import com.borderless.proxy.billing.config.ModelPricing;
import com.borderless.proxy.billing.dto.CostBreakdown;
import com.borderless.proxy.billing.dto.ReportedUsage;
import com.borderless.proxy.billing.dto.TokenComparison;
import com.borderless.proxy.billing.dto.TokenDelta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CostCalculator")
class CostCalculatorTest {

    private static final String MODEL = "test-model";

    /** 검산하기 쉬운 단가로 고정한다. 입력 100만 토큰당 $3, 출력 100만 토큰당 $15. */
    private static final ModelPricing PRICING =
            new ModelPricing(new BigDecimal("3.00"), new BigDecimal("15.00"));

    /** API가 보고한 실측 사용량. 입력 10,000 · 출력 2,000 토큰 */
    private static final ReportedUsage USAGE = new ReportedUsage(10_000, 2_000);

    /** 입력 12,000 → 8,000 (4,000 절감), 출력 3,000 → 2,000 (1,000 절감) */
    private static final TokenComparison COMPARISON =
            new TokenComparison(new TokenDelta(12_000, 8_000), new TokenDelta(3_000, 2_000));

    private final CostCalculator costCalculator = new CostCalculator(
            new BillingProperties(new BigDecimal("0.10"), Map.of(MODEL, PRICING)));

    @Nested
    @DisplayName("원가 환산")
    class ActualCost {

        @Test
        @DisplayName("API가 보고한 입력·출력 토큰에 각각의 단가를 적용해 원가를 구한다")
        void convertsReportedUsageToUsd() {
            // 10,000 × $3/1M + 2,000 × $15/1M = 0.03 + 0.03 = 0.06
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            assertThat(breakdown.actualCostUsd()).isEqualByComparingTo("0.060000");
        }

        @Test
        @DisplayName("원가는 절감 비교값이 아니라 API 보고값으로 계산한다")
        void actualCostIgnoresComparisonTokens() {
            // 비교값의 실제 토큰(입력 8,000 · 출력 2,000)으로 계산하면 0.024 + 0.03 = 0.054가 된다.
            // 시스템 프롬프트 오버헤드와 추론 토큰이 빠진 값이라 청구액과 어긋난다.
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            assertThat(breakdown.actualCostUsd()).isNotEqualByComparingTo("0.054000");
            assertThat(breakdown.actualCostUsd()).isEqualByComparingTo("0.060000");
        }

        @Test
        @DisplayName("금액은 UsageLog.costUsd와 같은 소수점 6자리로 맞춘다")
        void keepsSixDecimalScale() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            assertThat(breakdown.actualCostUsd().scale()).isEqualTo(6);
            assertThat(breakdown.savedCostUsd().scale()).isEqualTo(6);
            assertThat(breakdown.baselineCostUsd().scale()).isEqualTo(6);
            assertThat(breakdown.feeUsd().scale()).isEqualTo(6);
        }
    }

    @Nested
    @DisplayName("입력·출력 절감 합산")
    class SavingsAcrossInputAndOutput {

        @Test
        @DisplayName("입력과 출력 절감을 각각의 단가로 환산해 합산한다")
        void sumsInputAndOutputSavings() {
            // 입력 4,000 × $3/1M = 0.012, 출력 1,000 × $15/1M = 0.015
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            assertThat(breakdown.savedTokens()).isEqualTo(5_000);
            assertThat(breakdown.savedCostUsd()).isEqualByComparingTo("0.027000");
        }

        @Test
        @DisplayName("토큰 수를 먼저 합산해 단가 하나를 곱하는 방식과 결과가 다르다")
        void doesNotBlendRates() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            // 절감 5,000 토큰에 입력 단가만 적용하면 0.015, 출력 단가만 적용하면 0.075다.
            // 둘 다 실제 0.027과 다르다. 단가를 구간별로 적용해야 하는 이유를 고정한다.
            assertThat(breakdown.savedCostUsd()).isNotEqualByComparingTo("0.015000");
            assertThat(breakdown.savedCostUsd()).isNotEqualByComparingTo("0.075000");
        }

        @Test
        @DisplayName("출력 토큰이 더 적게 절감돼도 단가가 높아 금액 기여는 더 크다")
        void outputContributesMoreDespiteFewerTokens() {
            BigDecimal inputOnly = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(new TokenDelta(12_000, 8_000), TokenDelta.unchanged(2_000)))
                    .savedCostUsd();
            BigDecimal outputOnly = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(TokenDelta.unchanged(8_000), new TokenDelta(3_000, 2_000)))
                    .savedCostUsd();

            assertThat(inputOnly).isEqualByComparingTo("0.012000");
            assertThat(outputOnly).isEqualByComparingTo("0.015000");
            assertThat(outputOnly).isGreaterThan(inputOnly);
        }

        @Test
        @DisplayName("입력만 세면 출력 절감분이 통째로 누락된다")
        void ignoringOutputUnderstatesSavings() {
            CostBreakdown bothSides = costCalculator.calculate(MODEL, USAGE, COMPARISON);
            CostBreakdown inputOnly = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(new TokenDelta(12_000, 8_000), TokenDelta.unchanged(2_000)));

            assertThat(bothSides.savedCostUsd()).isGreaterThan(inputOnly.savedCostUsd());
            assertThat(bothSides.feeUsd()).isGreaterThan(inputOnly.feeUsd());
        }
    }

    @Nested
    @DisplayName("기준 비용")
    class BaselineCost {

        @Test
        @DisplayName("기준 비용은 원가에 회피 금액을 더한 값이다")
        void baselineIsActualPlusSaved() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            // 0.06 + 0.027 = 0.087
            assertThat(breakdown.baselineCostUsd()).isEqualByComparingTo("0.087000");
            assertThat(breakdown.baselineCostUsd())
                    .isEqualByComparingTo(breakdown.actualCostUsd().add(breakdown.savedCostUsd()));
        }

        @Test
        @DisplayName("절감분은 기준 비용에서 원가를 뺀 값과 정확히 일치한다")
        void savingsEqualsBaselineMinusActual() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, new ReportedUsage(7_777, 999),
                    new TokenComparison(new TokenDelta(9_999, 7_777), new TokenDelta(1_234, 999)));

            assertThat(breakdown.savedCostUsd())
                    .isEqualByComparingTo(breakdown.baselineCostUsd().subtract(breakdown.actualCostUsd()));
        }
    }

    @Nested
    @DisplayName("절약분 수수료")
    class SavingsFee {

        @Test
        @DisplayName("수수료는 절약한 금액의 10%다")
        void feeIsTenPercentOfSavings() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            // 0.027 × 0.10 = 0.0027
            assertThat(breakdown.feeUsd()).isEqualByComparingTo("0.002700");
        }

        @Test
        @DisplayName("수수료는 원가에 얹는 마크업이 아니라 절약분에만 붙는다")
        void feeAppliesToSavingsNotToCost() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE, COMPARISON);

            // 원가 마크업이라면 0.06 × 0.10 = 0.006이어야 한다. 그 값이 아님을 고정한다.
            assertThat(breakdown.feeUsd()).isNotEqualByComparingTo("0.006000");
            assertThat(breakdown.billedUsd()).isEqualByComparingTo("0.062700");
        }

        @Test
        @DisplayName("절약분이 없으면 수수료도 없고 원가만 청구한다")
        void noSavingsMeansNoFee() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(TokenDelta.unchanged(8_000), TokenDelta.unchanged(2_000)));

            assertThat(breakdown.savedTokens()).isZero();
            assertThat(breakdown.savedCostUsd()).isEqualByComparingTo("0");
            assertThat(breakdown.feeUsd()).isEqualByComparingTo("0");
            assertThat(breakdown.billedUsd()).isEqualByComparingTo(breakdown.actualCostUsd());
        }

        @Test
        @DisplayName("프록시가 역효과를 내면 절약분은 음수로 남기고 수수료는 0으로 막는다")
        void negativeSavingsChargesNoFee() {
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(new TokenDelta(8_000, 9_000), TokenDelta.unchanged(2_000)));

            assertThat(breakdown.savedTokens()).isEqualTo(-1_000);
            assertThat(breakdown.savedCostUsd()).isEqualByComparingTo("-0.003000");
            assertThat(breakdown.feeUsd()).isEqualByComparingTo("0");
            assertThat(breakdown.billedUsd()).isEqualByComparingTo(breakdown.actualCostUsd());
        }

        @Test
        @DisplayName("입력 절감이 출력 손해로 상쇄되면 순 절감만 수수료 대상이 된다")
        void nettingAcrossInputAndOutput() {
            // 입력 +5,000토큰(0.015) · 출력 -1,000토큰(-0.015) → 순 절감 0
            CostBreakdown breakdown = costCalculator.calculate(MODEL, USAGE,
                    new TokenComparison(new TokenDelta(13_000, 8_000), new TokenDelta(2_000, 3_000)));

            assertThat(breakdown.savedTokens()).isEqualTo(4_000);
            assertThat(breakdown.savedCostUsd()).isEqualByComparingTo("0");
            assertThat(breakdown.feeUsd()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("수수료 계산에서 6자리 아래는 반올림한다")
        void roundsFeeHalfUp() {
            // 입력 단가 $5.50/1M, 10토큰 절감 → 절약분 0.000055, 수수료 0.0000055 → 0.000006
            CostCalculator calculator = new CostCalculator(new BillingProperties(
                    new BigDecimal("0.10"),
                    Map.of(MODEL, new ModelPricing(new BigDecimal("5.50"), BigDecimal.ZERO))));

            CostBreakdown breakdown = calculator.calculate(MODEL, new ReportedUsage(0, 0),
                    new TokenComparison(new TokenDelta(10, 0), TokenDelta.unchanged(0)));

            assertThat(breakdown.savedCostUsd()).isEqualByComparingTo("0.000055");
            assertThat(breakdown.feeUsd()).isEqualByComparingTo("0.000006");
        }
    }

    @Nested
    @DisplayName("잘못된 입력")
    class InvalidInput {

        @Test
        @DisplayName("단가가 등록되지 않은 모델은 등록된 모델 목록과 함께 예외로 알린다")
        void unknownModelFails() {
            assertThatThrownBy(() -> costCalculator.calculate("없는모델", USAGE, COMPARISON))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("billing.models")
                    .hasMessageContaining("없는모델")
                    .hasMessageContaining(MODEL);
        }

        @Test
        @DisplayName("API 보고 사용량이 null이면 예외를 던진다")
        void nullUsageFails() {
            assertThatThrownBy(() -> costCalculator.calculate(MODEL, null, COMPARISON))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("usage");
        }

        @Test
        @DisplayName("비교 결과가 null이면 예외를 던진다")
        void nullComparisonFails() {
            assertThatThrownBy(() -> costCalculator.calculate(MODEL, USAGE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("comparison");
        }

        @Test
        @DisplayName("API 보고 토큰 수가 음수면 예외를 던진다")
        void negativeReportedUsageFails() {
            assertThatThrownBy(() -> new ReportedUsage(-1, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("inputTokens");
            assertThatThrownBy(() -> new ReportedUsage(0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outputTokens");
        }
    }
}
