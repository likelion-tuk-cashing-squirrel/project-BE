package com.borderless.proxy.billing.config;

import java.math.BigDecimal;

/**
 * 모델 하나의 토큰 단가.
 *
 * <p>단가 기준을 <b>100만 토큰당 USD</b>로 잡았다. OpenAI·Anthropic 등 공급자가 가격표를
 * 그 단위로 공시하기 때문에, 공시값을 그대로 yaml에 옮겨 적을 수 있어야 오타와 환산 실수가 줄어든다.
 * 토큰 1개당 단가로 두면 {@code 0.00000015} 같은 값을 적어야 해서 자리수를 세다 틀리기 쉽다.
 *
 * @param inputPerMillion  입력 100만 토큰당 USD
 * @param outputPerMillion 출력 100만 토큰당 USD
 */
public record ModelPricing(
        BigDecimal inputPerMillion,
        BigDecimal outputPerMillion
) {

    public ModelPricing {
        if (inputPerMillion == null) {
            throw new IllegalArgumentException("billing.models.*.input-per-million은 null일 수 없습니다.");
        }
        if (outputPerMillion == null) {
            throw new IllegalArgumentException("billing.models.*.output-per-million은 null일 수 없습니다.");
        }
        if (inputPerMillion.signum() < 0) {
            throw new IllegalArgumentException(
                    "billing.models.*.input-per-million은 음수일 수 없습니다: " + inputPerMillion);
        }
        if (outputPerMillion.signum() < 0) {
            throw new IllegalArgumentException(
                    "billing.models.*.output-per-million은 음수일 수 없습니다: " + outputPerMillion);
        }
    }
}
