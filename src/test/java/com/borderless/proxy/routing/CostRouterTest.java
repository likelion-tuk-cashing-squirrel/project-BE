package com.borderless.proxy.routing;

import com.borderless.proxy.routing.config.RoutingProperties;
import com.borderless.proxy.routing.dto.LanguageDetection;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CostRouter")
class CostRouterTest {

    private static final int TOKEN_THRESHOLD = 50;
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    private final RoutingProperties routingProperties =
            new RoutingProperties(TOKEN_THRESHOLD, CONFIDENCE_THRESHOLD);

    private final CostRouter costRouter =
            new CostRouter(new TokenCalculator(), new LanguageDetector(), routingProperties);

    @Nested
    @DisplayName("스펙 테스트 케이스 표")
    class SpecScenarios {

        static Stream<Arguments> specCases() {
            return Stream.of(
                    Arguments.of("짧은 영어 인사말", RoutingSamples.SHORT_ENGLISH, RoutingTier.SKIP),
                    Arguments.of("짧은 베트남어 문장", RoutingSamples.SHORT_VIETNAMESE, RoutingTier.SKIP),
                    Arguments.of("짧은 필리핀어 문장", RoutingSamples.SHORT_TAGALOG, RoutingTier.SKIP),
                    Arguments.of("긴 영어 문단", RoutingSamples.LONG_ENGLISH, RoutingTier.TIER_1),
                    Arguments.of("긴 베트남어 문단", RoutingSamples.LONG_VIETNAMESE, RoutingTier.TIER_2),
                    Arguments.of("긴 필리핀어 문단", RoutingSamples.LONG_TAGALOG, RoutingTier.TIER_3),
                    Arguments.of("이모지·숫자만", RoutingSamples.EMOJI_AND_DIGITS_ONLY, RoutingTier.TIER_1),
                    Arguments.of("빈 문자열", "", RoutingTier.SKIP)
            );
        }

        @ParameterizedTest(name = "{0} → {2}")
        @MethodSource("specCases")
        @DisplayName("스펙에 정의된 입력별 기대 티어를 만족한다")
        void routesAsSpecified(String label, String text, RoutingTier expectedTier) {
            assertThat(costRouter.route(text).tier()).isEqualTo(expectedTier);
        }
    }

    @Nested
    @DisplayName("판단 근거를 함께 반환한다")
    class ReturnsEvidence {

        @Test
        @DisplayName("SKIP은 토큰 수만 담고 언어는 미확정으로 남긴다")
        void skipCarriesTokensOnly() {
            RoutingResult result = costRouter.route(RoutingSamples.SHORT_ENGLISH);

            assertThat(result.tier()).isEqualTo(RoutingTier.SKIP);
            assertThat(result.estimatedTokens()).isEqualTo(9);
            assertThat(result.detectedLanguage()).isEqualTo(RoutingResult.UNDETERMINED_LANGUAGE);
            assertThat(result.confidence()).isZero();
        }

        @Test
        @DisplayName("티어가 결정되면 감지 언어와 신뢰도를 담는다")
        void tieredResultCarriesLanguageAndConfidence() {
            RoutingResult result = costRouter.route(RoutingSamples.LONG_VIETNAMESE);

            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_2);
            assertThat(result.detectedLanguage()).isEqualTo("vi");
            assertThat(result.estimatedTokens()).isEqualTo(86);
            assertThat(result.confidence()).isGreaterThan(CONFIDENCE_THRESHOLD);
        }

        @Test
        @DisplayName("결정된 티어가 후속 단계 수행 여부를 알려준다")
        void tierExposesPipelineFlags() {
            RoutingTier tagalog = costRouter.route(RoutingSamples.LONG_TAGALOG).tier();
            RoutingTier english = costRouter.route(RoutingSamples.LONG_ENGLISH).tier();
            RoutingTier skipped = costRouter.route(RoutingSamples.SHORT_ENGLISH).tier();

            assertThat(tagalog.requiresMorphologyHint()).isTrue();
            assertThat(tagalog.requiresPivot()).isTrue();
            assertThat(tagalog.requiresRetranslation()).isTrue();

            assertThat(english.requiresMasking()).isTrue();
            assertThat(english.requiresPivot()).isFalse();
            assertThat(english.requiresRetranslation()).isFalse();

            assertThat(skipped.requiresMasking()).isFalse();
            assertThat(skipped.requiresPivot()).isFalse();
        }
    }

    @Nested
    @DisplayName("매핑 테이블에 없는 언어는 TIER_1로 폴백한다")
    class UnmappedLanguage {

        @Test
        @DisplayName("한국어 문단은 감지에 성공해도 TIER_1로 간다")
        void koreanFallsBackToTier1() {
            RoutingResult result = costRouter.route(RoutingSamples.LONG_KOREAN);

            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_1);
            assertThat(result.detectedLanguage()).isEqualTo("ko");
            assertThat(result.confidence()).isGreaterThan(CONFIDENCE_THRESHOLD);
        }
    }

    @Nested
    @DisplayName("토큰 임계치 경계")
    class TokenThresholdBoundary {

        private final TokenCalculator tokenCalculator = mock(TokenCalculator.class);
        private final LanguageDetector languageDetector = mock(LanguageDetector.class);
        private final CostRouter router = new CostRouter(tokenCalculator, languageDetector, routingProperties);

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 25, TOKEN_THRESHOLD - 1})
        @DisplayName("임계치 미만이면 언어 감지 없이 SKIP한다")
        void skipsBelowThreshold(int tokens) {
            when(tokenCalculator.countTokens(anyString())).thenReturn(tokens);

            RoutingResult result = router.route("무엇이든");

            assertThat(result.tier()).isEqualTo(RoutingTier.SKIP);
            assertThat(result.estimatedTokens()).isEqualTo(tokens);
            verify(languageDetector, never()).detect(anyString());
        }

        @ParameterizedTest
        @ValueSource(ints = {TOKEN_THRESHOLD, TOKEN_THRESHOLD + 1, 1_000})
        @DisplayName("임계치 이상이면 언어 감지로 넘어간다")
        void detectsAtOrAboveThreshold(int tokens) {
            when(tokenCalculator.countTokens(anyString())).thenReturn(tokens);
            when(languageDetector.detect(anyString())).thenReturn(new LanguageDetection("vi", 0.99));

            RoutingResult result = router.route("무엇이든");

            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_2);
            assertThat(result.estimatedTokens()).isEqualTo(tokens);
            verify(languageDetector).detect(anyString());
        }
    }

    @Nested
    @DisplayName("신뢰도 임계치 경계")
    class ConfidenceThresholdBoundary {

        private final TokenCalculator tokenCalculator = mock(TokenCalculator.class);
        private final LanguageDetector languageDetector = mock(LanguageDetector.class);
        private final CostRouter router = new CostRouter(tokenCalculator, languageDetector, routingProperties);

        @ParameterizedTest
        @CsvSource({
                "vi, 0.0",
                "vi, 0.5",
                "vi, 0.69",
                "tl, 0.3",
                "und, 0.0"
        })
        @DisplayName("신뢰도가 임계치 미만이면 감지 언어와 무관하게 TIER_1로 폴백한다")
        void fallsBackBelowConfidenceThreshold(String languageCode, double confidence) {
            when(tokenCalculator.countTokens(anyString())).thenReturn(100);
            when(languageDetector.detect(anyString())).thenReturn(new LanguageDetection(languageCode, confidence));

            RoutingResult result = router.route("무엇이든");

            assertThat(result.tier()).isEqualTo(RoutingTier.TIER_1);
            assertThat(result.detectedLanguage()).isEqualTo(languageCode);
            assertThat(result.confidence()).isEqualTo(confidence);
        }

        @ParameterizedTest
        @CsvSource({
                "en, 0.7,  TIER_1",
                "vi, 0.7,  TIER_2",
                "vi, 0.99, TIER_2",
                "tl, 0.7,  TIER_3",
                "tl, 1.0,  TIER_3"
        })
        @DisplayName("신뢰도가 임계치 이상이면 매핑 테이블을 따른다")
        void usesMappingAtOrAboveConfidenceThreshold(String languageCode, double confidence, RoutingTier expected) {
            when(tokenCalculator.countTokens(anyString())).thenReturn(100);
            when(languageDetector.detect(anyString())).thenReturn(new LanguageDetection(languageCode, confidence));

            assertThat(router.route("무엇이든").tier()).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("비정상 입력")
    class InvalidInput {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", "\t\n", " \t\n\r\u00A0  "})
        @DisplayName("null·빈 문자열·공백만 있는 입력은 예외 없이 SKIP한다")
        void blankInputSkips(String text) {
            RoutingResult result = costRouter.route(text);

            assertThat(result.tier()).isEqualTo(RoutingTier.SKIP);
            assertThat(result.estimatedTokens()).isZero();
        }
    }
}
