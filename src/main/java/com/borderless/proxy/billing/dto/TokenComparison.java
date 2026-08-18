package com.borderless.proxy.billing.dto;

/**
 * 입력과 출력 양쪽의 토큰 절감 비교 결과.
 *
 * <p><b>출력을 함께 보는 이유.</b> 출력 단가가 입력의 4배 수준이고(gpt-4o 기준 $10 vs $2.50 / 1M)
 * 실측 로그에서도 출력 토큰이 입력보다 많다. 입력만 세면 절감분을 크게 과소계상한다.
 *
 * <p><b>출력 기준값을 어떻게 얻는가.</b> 피벗 흐름에서는 STEP 04가 영어로 답하고 STEP 05가 그걸
 * 원어로 재번역한다. 그래서 영어 답변(실제로 지불한 것)과 원어 답변(원어로 물었다면 모델이 냈을 것)이
 * 둘 다 실제 텍스트로 남는다. 원어 답변 토큰 수를 기준값으로 쓴다.
 * 네이티브 생성 답변과 번역문이 완전히 같지는 않으니 <b>추정치</b>지만, 측정된 텍스트에서 나온 값이다.
 *
 * <p><b>추론 토큰은 절감분에서 빠진다.</b> 내부 추론 토큰은 응답에 내용이 담기지 않아 셀 텍스트가 없고,
 * 원어로 물었을 때 추론이 얼마나 늘었을지는 알 방법이 없다. 원가에는 {@link ReportedUsage}를 통해
 * 포함되지만 절감 근거로는 쓰지 않는다. 즉 추론 토큰은 기준과 실제에 동일하다고 가정한다.
 *
 * @param input  입력 토큰 비교
 * @param output 출력 토큰 비교. 피벗을 안 태웠으면 {@link TokenDelta#unchanged(int)}
 */
public record TokenComparison(
        TokenDelta input,
        TokenDelta output
) {

    public TokenComparison {
        if (input == null) {
            throw new IllegalArgumentException("input은 null일 수 없습니다.");
        }
        if (output == null) {
            throw new IllegalArgumentException("output은 null일 수 없습니다.");
        }
    }

    /** 절감한 입력 토큰 수. 역효과가 났으면 음수 */
    public int savedInputTokens() {
        return input.saved();
    }

    /** 절감한 출력 토큰 수. 역효과가 났으면 음수 */
    public int savedOutputTokens() {
        return output.saved();
    }

    /**
     * 절감한 총 토큰 수. {@code UsageLog.savedTokens}에 저장할 값이다.
     *
     * <p>입력과 출력을 단순 합산한다. 단가가 다르므로 이 숫자로 금액을 환산하면 안 된다.
     * 금액은 구간별 단가를 적용한 {@code CostBreakdown.savedCostUsd()}를 쓴다.
     */
    public int savedTokens() {
        return savedInputTokens() + savedOutputTokens();
    }
}
