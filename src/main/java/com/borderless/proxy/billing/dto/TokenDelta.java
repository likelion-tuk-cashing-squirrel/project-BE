package com.borderless.proxy.billing.dto;

/**
 * 프록시를 거치기 전과 후의 토큰 수 한 쌍. 입력 쪽과 출력 쪽에 각각 쓴다.
 *
 * <p>두 값은 <b>같은 토크나이저로 같은 범위의 텍스트를 센 것</b>이어야 한다. 한쪽만 API 보고값을
 * 쓰면 시스템 프롬프트 오버헤드가 한쪽에만 들어가 차액이 그만큼 틀어진다. 원가는
 * {@link ReportedUsage}로 따로 계산하고, 이 타입은 절감분 산정에만 쓴다.
 *
 * @param baselineTokens 프록시를 안 썼다면 들었을 토큰 수
 * @param actualTokens   프록시를 거쳐 실제로 오간 토큰 수
 */
public record TokenDelta(
        int baselineTokens,
        int actualTokens
) {

    public TokenDelta {
        if (baselineTokens < 0) {
            throw new IllegalArgumentException("baselineTokens는 음수일 수 없습니다: " + baselineTokens);
        }
        if (actualTokens < 0) {
            throw new IllegalArgumentException("actualTokens는 음수일 수 없습니다: " + actualTokens);
        }
    }

    /**
     * 절감이 없는 구간. 피벗을 안 태운 요청의 출력 쪽처럼 비교 대상이 없을 때 쓴다.
     *
     * @param tokens 기준과 실제가 동일한 토큰 수
     */
    public static TokenDelta unchanged(int tokens) {
        return new TokenDelta(tokens, tokens);
    }

    /**
     * 절감한 토큰 수.
     *
     * <p><b>음수를 그대로 반환한다.</b> 마스킹 토큰이 원래 용어보다 길거나 피벗 번역문이 원문보다
     * 장황해지면 실제로 손해를 본 것이다. 0으로 깎으면 역효과 난 요청을 지표에서 찾을 수 없다.
     */
    public int saved() {
        return baselineTokens - actualTokens;
    }
}
