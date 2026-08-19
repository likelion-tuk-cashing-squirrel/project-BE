package com.borderless.proxy.routing;

import com.borderless.proxy.routing.dto.LanguageDetection;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LanguageDetector")
class LanguageDetectorTest {

    private final LanguageDetector languageDetector = new LanguageDetector();

    @Nested
    @DisplayName("라우팅 대상 언어를 감지한다")
    class DetectsTargetLanguages {

        @Test
        @DisplayName("영어 문단은 en으로 감지한다")
        void detectsEnglish() {
            LanguageDetection detection = languageDetector.detect(RoutingSamples.LONG_ENGLISH);

            assertThat(detection.languageCode()).isEqualTo("en");
            assertThat(detection.confidence()).isGreaterThan(0.9);
            assertThat(detection.isUndetermined()).isFalse();
        }

        @Test
        @DisplayName("베트남어 문단은 vi로 감지한다")
        void detectsVietnamese() {
            LanguageDetection detection = languageDetector.detect(RoutingSamples.LONG_VIETNAMESE);

            assertThat(detection.languageCode()).isEqualTo("vi");
            assertThat(detection.confidence()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("필리핀어 문단은 tl로 감지한다")
        void detectsTagalog() {
            LanguageDetection detection = languageDetector.detect(RoutingSamples.LONG_TAGALOG);

            assertThat(detection.languageCode()).isEqualTo("tl");
            assertThat(detection.confidence()).isGreaterThan(0.9);
        }

        @Test
        @DisplayName("신뢰도는 항상 0.0 ~ 1.0 범위다")
        void confidenceStaysWithinRange() {
            assertThat(languageDetector.detect(RoutingSamples.LONG_ENGLISH).confidence()).isBetween(0.0, 1.0);
            assertThat(languageDetector.detect(RoutingSamples.LONG_VIETNAMESE).confidence()).isBetween(0.0, 1.0);
            assertThat(languageDetector.detect(RoutingSamples.LONG_TAGALOG).confidence()).isBetween(0.0, 1.0);
        }
    }

    @Nested
    @DisplayName("언어를 특정할 수 없으면 undetermined로 폴백한다")
    class FallsBackWhenUndetermined {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "   ", " \t\n\r\u00A0  "})
        @DisplayName("null·빈 문자열·공백만 있는 입력")
        void nullOrBlankInput(String text) {
            LanguageDetection detection = languageDetector.detect(text);

            assertThat(detection.isUndetermined()).isTrue();
            assertThat(detection.languageCode()).isEqualTo(RoutingResult.UNDETERMINED_LANGUAGE);
            assertThat(detection.confidence()).isZero();
        }

        @Test
        @DisplayName("이모지와 숫자만 있는 입력")
        void emojiAndDigitsOnly() {
            LanguageDetection detection = languageDetector.detect(RoutingSamples.EMOJI_AND_DIGITS_ONLY);

            assertThat(detection.isUndetermined()).isTrue();
            assertThat(detection.confidence()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"?", "...", "123", "🚀", "!@#$%^&*()"})
        @DisplayName("언어 특성이 없는 아주 짧은 입력")
        void tooShortToDetect(String text) {
            LanguageDetection detection = languageDetector.detect(text);

            assertThat(detection.isUndetermined()).isTrue();
        }

        @Test
        @DisplayName("감지에 실패해도 예외를 던지지 않는다")
        void neverThrows() {
            assertThat(languageDetector.detect(RoutingSamples.WHITESPACE_ONLY)).isNotNull();
            assertThat(languageDetector.detect(RoutingSamples.EMOJI_AND_DIGITS_ONLY)).isNotNull();
            assertThat(languageDetector.detect(RoutingSamples.VERY_LONG_TEXT)).isNotNull();
        }
    }

    @Test
    @DisplayName("아주 긴 텍스트도 예외 없이 감지한다")
    void handlesVeryLongText() {
        LanguageDetection detection = languageDetector.detect(RoutingSamples.VERY_LONG_TEXT);

        assertThat(detection.languageCode()).isEqualTo("en");
    }
}
