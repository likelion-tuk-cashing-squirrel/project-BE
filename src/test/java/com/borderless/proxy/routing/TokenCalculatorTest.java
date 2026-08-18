package com.borderless.proxy.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenCalculator")
class TokenCalculatorTest {

    private final TokenCalculator tokenCalculator = new TokenCalculator();

    @Test
    @DisplayName("o200k_base 기준으로 토큰 수를 계산한다")
    void countsTokensWithO200kBase() {
        // o200k_base 실측값. 인코딩이 CL100K 등으로 바뀌면 이 값이 깨지므로 회귀 방지용으로 고정한다.
        assertThat(tokenCalculator.countTokens("Hello, world!")).isEqualTo(4);
        assertThat(tokenCalculator.countTokens(RoutingSamples.SHORT_ENGLISH)).isEqualTo(9);
        assertThat(tokenCalculator.countTokens(RoutingSamples.LONG_ENGLISH)).isEqualTo(58);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", " \t\n\r\u00A0  "})
    @DisplayName("null·빈 문자열·공백만 있는 입력은 0 토큰이다")
    void returnsZeroForNullOrBlank(String text) {
        assertThat(tokenCalculator.countTokens(text)).isZero();
    }

    @Test
    @DisplayName("같은 의미라도 비영어권 언어가 영어보다 토큰을 더 많이 소모한다")
    void nonEnglishConsumesMoreTokensThanEnglish() {
        int english = tokenCalculator.countTokens(RoutingSamples.EQUIVALENT_ENGLISH);
        int vietnamese = tokenCalculator.countTokens(RoutingSamples.EQUIVALENT_VIETNAMESE);
        int tagalog = tokenCalculator.countTokens(RoutingSamples.EQUIVALENT_TAGALOG);

        assertThat(vietnamese).isGreaterThan(english);
        assertThat(tagalog).isGreaterThan(english);
    }

    @Test
    @DisplayName("긴 문단은 짧은 문장보다 토큰 수가 많다")
    void longerTextProducesMoreTokens() {
        assertThat(tokenCalculator.countTokens(RoutingSamples.LONG_ENGLISH))
                .isGreaterThan(tokenCalculator.countTokens(RoutingSamples.SHORT_ENGLISH));
        assertThat(tokenCalculator.countTokens(RoutingSamples.LONG_VIETNAMESE))
                .isGreaterThan(tokenCalculator.countTokens(RoutingSamples.SHORT_VIETNAMESE));
    }

    @Test
    @DisplayName("Encoding 인스턴스를 재사용하므로 반복 호출 결과가 동일하다")
    void repeatedCallsReturnSameCount() {
        int first = tokenCalculator.countTokens(RoutingSamples.LONG_TAGALOG);

        for (int i = 0; i < 100; i++) {
            assertThat(tokenCalculator.countTokens(RoutingSamples.LONG_TAGALOG)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("이모지·숫자만 있는 텍스트도 예외 없이 계산한다")
    void handlesEmojiAndDigits() {
        assertThat(tokenCalculator.countTokens(RoutingSamples.EMOJI_AND_DIGITS_ONLY)).isPositive();
    }

    @Test
    @DisplayName("아주 긴 텍스트도 예외 없이 계산한다")
    void handlesVeryLongText() {
        assertThat(tokenCalculator.countTokens(RoutingSamples.VERY_LONG_TEXT)).isGreaterThan(10_000);
    }
}
