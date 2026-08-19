package com.borderless.proxy.routing;

import com.borderless.proxy.routing.config.RoutingProperties;
import com.borderless.proxy.routing.dto.LanguageDetection;
import com.borderless.proxy.routing.dto.RoutingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 파이프라인 STEP 01. 요청마다 어떤 처리 경로를 탈지 판별한다.
 *
 * <p>판별 순서
 * <ol>
 *   <li>토큰 수 계산</li>
 *   <li>임계치 미만이면 {@link RoutingTier#SKIP} 반환 (언어 감지 자체를 생략)</li>
 *   <li>임계치 이상이면 언어 감지 후 매핑 테이블로 티어 결정</li>
 *   <li>감지 신뢰도가 낮으면 {@link RoutingTier#TIER_1}로 폴백</li>
 * </ol>
 *
 * <p>이 컴포넌트는 판단만 하고 텍스트를 변형하지 않는다.
 * 마스킹·번역·형태소 분석·LLM 호출·비용 환산은 모두 다른 컴포넌트의 책임이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostRouter {

    private final TokenCalculator tokenCalculator;
    private final LanguageDetector languageDetector;
    private final RoutingProperties routingProperties;

    /**
     * 텍스트를 보고 처리 경로를 결정한다.
     *
     * @param text 클라이언트가 보낸 원문. {@code null}이나 공백만 있으면 0 토큰으로 취급되어 SKIP된다.
     * @return 결정된 경로와 판단 근거(감지 언어, 토큰 수, 신뢰도)
     */
    public RoutingResult route(String text) {
        int estimatedTokens = tokenCalculator.countTokens(text);

        if (estimatedTokens < routingProperties.tokenThreshold()) {
            log.debug("토큰 수 {}가 임계치 {} 미만이라 SKIP합니다.",
                    estimatedTokens, routingProperties.tokenThreshold());
            return RoutingResult.skip(estimatedTokens);
        }

        LanguageDetection detection = languageDetector.detect(text);

        if (detection.confidence() < routingProperties.confidenceThreshold()) {
            log.debug("언어 감지 신뢰도 {}가 임계치 {} 미만이라 {}로 폴백합니다. 감지 언어={}",
                    detection.confidence(), routingProperties.confidenceThreshold(),
                    RoutingTier.FALLBACK, detection.languageCode());
            return new RoutingResult(
                    RoutingTier.FALLBACK, detection.languageCode(), estimatedTokens, detection.confidence());
        }

        RoutingTier tier = RoutingTier.fromLanguageCode(detection.languageCode());
        log.debug("라우팅 결정: tier={}, language={}, tokens={}, confidence={}",
                tier, detection.languageCode(), estimatedTokens, detection.confidence());

        return new RoutingResult(tier, detection.languageCode(), estimatedTokens, detection.confidence());
    }
}
