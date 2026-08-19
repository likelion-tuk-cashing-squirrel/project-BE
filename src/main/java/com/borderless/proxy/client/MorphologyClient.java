package com.borderless.proxy.client;

import com.borderless.proxy.client.dto.MorphologyHints;
import reactor.core.publisher.Mono;

/**
 * 형태소 힌트 조회 통로 (파이프라인 STEP 03-B).
 *
 * <p>{@code RoutingTier.TIER_3}(타갈로그)에서만 호출한다.
 * 다른 티어는 {@code requiresMorphologyHint()}가 {@code false}다.
 *
 * <p>반환 타입이 {@code Mono}이므로 블로킹 여부는 호출측(오케스트레이터)이 결정한다.
 */
public interface MorphologyClient {

    /**
     * 텍스트의 어근과 접사를 분해해 힌트를 가져온다.
     *
     * <p>텍스트를 변형하지 않는다. 힌트를 프롬프트에 어떻게 끼워넣을지는 호출부가 결정한다.
     *
     * @param text 마스킹이 끝난 타갈로그 텍스트. {@code {TERM_01}} 같은 치환 토큰은 분석에서 제외된다
     * @return 힌트. 조회에 실패했고 {@code app.morphology.optional}이 켜져 있으면
     *         {@link MorphologyHints#empty(String)}를 돌려준다
     */
    Mono<MorphologyHints> hints(String text);
}
