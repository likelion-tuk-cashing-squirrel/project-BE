package com.borderless.proxy.routing;

import com.borderless.proxy.routing.config.RoutingProperties;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 라우팅 컴포넌트를 실제 스프링 빈으로 조립하고 {@code application.yaml}의 임계치를 그대로 읽어
 * 실제 다국어 문장이 끝까지 통과하는지 확인한다.
 *
 * <p>DB에 의존하지 않도록 전체 애플리케이션 컨텍스트 대신 라우팅 빈만 올린다.
 */
@DisplayName("라우팅 스모크 테스트")
class RoutingSmokeTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RoutingConfig.class);

    @Test
    @DisplayName("실제 영어·베트남어·필리핀어 문장이 전체 라우팅 흐름을 예외 없이 통과한다")
    void realWorldSentencesFlowThroughRouter() {
        contextRunner.run(context -> {
            CostRouter router = context.getBean(CostRouter.class);
            RoutingProperties properties = context.getBean(RoutingProperties.class);

            RoutingResult english = router.route(RoutingSamples.LONG_ENGLISH);
            RoutingResult vietnamese = router.route(RoutingSamples.LONG_VIETNAMESE);
            RoutingResult tagalog = router.route(RoutingSamples.LONG_TAGALOG);

            assertThat(english.tier()).isEqualTo(RoutingTier.TIER_1);
            assertThat(vietnamese.tier()).isEqualTo(RoutingTier.TIER_2);
            assertThat(tagalog.tier()).isEqualTo(RoutingTier.TIER_3);

            // 세 결과 모두 판단 근거가 빠짐없이 채워져 있어야 한다.
            for (RoutingResult result : new RoutingResult[]{english, vietnamese, tagalog}) {
                assertThat(result.detectedLanguage()).isNotBlank();
                assertThat(result.detectedLanguage()).isNotEqualTo(RoutingResult.UNDETERMINED_LANGUAGE);
                assertThat(result.estimatedTokens()).isGreaterThanOrEqualTo(properties.tokenThreshold());
                assertThat(result.confidence()).isBetween(properties.confidenceThreshold(), 1.0);
            }
        });
    }

    @Test
    @DisplayName("application.yaml의 임계치가 라우터에 그대로 주입된다")
    void usesThresholdsFromApplicationYaml() {
        contextRunner.run(context -> {
            RoutingProperties properties = context.getBean(RoutingProperties.class);

            assertThat(properties.tokenThreshold()).isEqualTo(50);
            assertThat(properties.confidenceThreshold()).isEqualTo(0.7);
        });
    }

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCases {

        private final CostRouter router =
                new CostRouter(new TokenCalculator(), new LanguageDetector(), new RoutingProperties(50, 0.7));

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("null과 빈 문자열은 0 토큰으로 SKIP한다")
        void nullAndEmpty(String text) {
            RoutingResult result = router.route(text);

            assertThat(result.tier()).isEqualTo(RoutingTier.SKIP);
            assertThat(result.estimatedTokens()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {" ", "        ", "\t", "\n\n\n", "\r\n", " \t\n\r\u00A0  "})
        @DisplayName("공백만 있는 문자열은 0 토큰으로 SKIP한다")
        void whitespaceOnly(String text) {
            RoutingResult result = router.route(text);

            assertThat(result.tier()).isEqualTo(RoutingTier.SKIP);
            assertThat(result.estimatedTokens()).isZero();
        }

        @Test
        @DisplayName("이모지만 있는 짧은 텍스트는 임계치 미만이라 SKIP한다")
        void shortEmojiOnly() {
            assertThat(router.route(RoutingSamples.EMOJI_ONLY).tier()).isEqualTo(RoutingTier.SKIP);
        }

        @Test
        @DisplayName("이모지만 있는 긴 텍스트는 언어를 특정할 수 없어 TIER_1로 폴백한다")
        void longEmojiOnly() {
            RoutingResult result = router.route(RoutingSamples.LONG_EMOJI_ONLY);

            assertThat(result.estimatedTokens()).isGreaterThanOrEqualTo(50);
            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_1);
            assertThat(result.detectedLanguage()).isEqualTo(RoutingResult.UNDETERMINED_LANGUAGE);
            assertThat(result.confidence()).isZero();
        }

        @Test
        @DisplayName("아주 긴 텍스트도 예외 없이 라우팅한다")
        void veryLongText() {
            assertThatCode(() -> router.route(RoutingSamples.VERY_LONG_TEXT)).doesNotThrowAnyException();

            RoutingResult result = router.route(RoutingSamples.VERY_LONG_TEXT);

            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_1);
            assertThat(result.estimatedTokens()).isGreaterThan(10_000);
        }

        @Test
        @DisplayName("여러 언어가 섞인 텍스트도 예외 없이 하나의 티어로 수렴한다")
        void mixedLanguages() {
            String mixed = RoutingSamples.LONG_ENGLISH + " " + RoutingSamples.LONG_VIETNAMESE
                    + " " + RoutingSamples.LONG_TAGALOG;

            RoutingResult result = router.route(mixed);

            assertThat(result.tier()).isIn(RoutingTier.TIER_1, RoutingTier.TIER_2, RoutingTier.TIER_3);
            assertThat(result.confidence()).isBetween(0.0, 1.0);
        }
    }

    @Configuration
    @EnableConfigurationProperties(RoutingProperties.class)
    @Import({TokenCalculator.class, LanguageDetector.class, CostRouter.class})
    static class RoutingConfig {
    }
}
