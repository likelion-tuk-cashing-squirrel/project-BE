package com.borderless.proxy.routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 라우팅 임계치 설정.
 *
 * <p>실측 데이터로 조정할 값이라 코드에 하드코딩하지 않고 {@code application.yaml}에서 주입받는다.
 *
 * @param tokenThreshold      이 토큰 수 미만이면 마스킹·피벗을 생략하고 SKIP으로 통과시킨다.
 *                            o200k_base 기준이며, 짧은 인사말(약 10 토큰)과
 *                            실제 업무 문단(약 60 토큰 이상)을 가르는 지점으로 잡았다.
 * @param confidenceThreshold 언어 감지 신뢰도가 이 값 미만이면 오판으로 보고 TIER_1로 폴백한다.
 */
@ConfigurationProperties(prefix = "routing")
public record RoutingProperties(

        @DefaultValue("50") int tokenThreshold,

        @DefaultValue("0.7") double confidenceThreshold
) {

    public RoutingProperties {
        if (tokenThreshold < 0) {
            throw new IllegalArgumentException("routing.token-threshold는 음수일 수 없습니다: " + tokenThreshold);
        }
        if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "routing.confidence-threshold는 0.0 ~ 1.0 범위여야 합니다: " + confidenceThreshold);
        }
    }
}
