package com.borderless.proxy.billing;

import com.borderless.proxy.billing.dto.TokenDelta;
import com.borderless.proxy.routing.TokenCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenSavingsCalculator")
class TokenSavingsCalculatorTest {

    private final TokenSavingsCalculator savingsCalculator =
            new TokenSavingsCalculator(new TokenCalculator());

    @Nested
    @DisplayName("입력 절감")
    class InputSavings {

        @Test
        @DisplayName("마스킹으로 짧아진 만큼 절감분으로 잡는다")
        void shorterSentTextProducesSavings() {
            TokenDelta delta = savingsCalculator.compareInput(
                    "우리 회사의 프로젝트 관리 시스템에 대해 자세히 설명해 주세요.", "{TERM_01}");

            assertThat(delta.baselineTokens()).isGreaterThan(delta.actualTokens());
            assertThat(delta.saved()).isEqualTo(delta.baselineTokens() - delta.actualTokens());
            assertThat(delta.saved()).isPositive();
        }

        @Test
        @DisplayName("텍스트가 그대로면 절감분은 0이다")
        void unchangedTextSavesNothing() {
            String text = "Explain the project management system in detail.";

            assertThat(savingsCalculator.compareInput(text, text).saved()).isZero();
        }

        @Test
        @DisplayName("전송 텍스트가 더 길면 절감분을 음수로 남긴다")
        void longerSentTextProducesNegativeSavings() {
            TokenDelta delta = savingsCalculator.compareInput(
                    "짧은 질문", "{TERM_01} {TERM_02} {TERM_03} 짧은 질문");

            assertThat(delta.saved()).isNegative();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t\n"})
        @DisplayName("null·빈 문자열·공백만 있는 전송 텍스트는 0 토큰으로 취급한다")
        void blankSentTextCountsAsZero(String blank) {
            // "Hello, world!"는 o200k_base 기준 4토큰. 인코딩이 바뀌면 이 값이 깨진다.
            TokenDelta delta = savingsCalculator.compareInput("Hello, world!", blank);

            assertThat(delta.baselineTokens()).isEqualTo(4);
            assertThat(delta.actualTokens()).isZero();
            assertThat(delta.saved()).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("출력 절감")
    class OutputSavings {

        @Test
        @DisplayName("원어 답변이 영어 답변보다 토큰을 더 먹으면 그 차이를 절감분으로 잡는다")
        void nativeAnswerHeavierThanEnglishProducesSavings() {
            // 같은 뜻의 문장이라도 비영어권 언어가 토큰을 더 쓴다.
            // 영어로 답변받고 재번역하면 그 차이만큼 출력 비용을 회피한 것이다.
            TokenDelta delta = savingsCalculator.compareOutput(
                    "Tôi cần kiểm tra lại cấu hình của hệ thống quản lý dự án trước khi triển khai.",
                    "You need to review the project management system configuration before deploying.");

            assertThat(delta.baselineTokens()).isGreaterThan(delta.actualTokens());
            assertThat(delta.saved()).isPositive();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t\n"})
        @DisplayName("재번역을 안 한 흐름은 출력 절감을 0으로 두고 실제 토큰 수만 남긴다")
        void missingNativeAnswerMeansNoOutputSavings(String blank) {
            String answer = "The rollback steps are documented in the runbook.";
            int answerTokens = new TokenCalculator().countTokens(answer);

            TokenDelta delta = savingsCalculator.compareOutput(blank, answer);

            // 기준값을 0으로 두면 절감분이 출력 토큰 전체만큼 음수가 되어 지표가 망가진다.
            assertThat(delta.saved()).isZero();
            assertThat(delta.baselineTokens()).isEqualTo(answerTokens);
            assertThat(delta.actualTokens()).isEqualTo(answerTokens);
        }

        @Test
        @DisplayName("재번역문이 영어 답변보다 짧으면 절감분을 음수로 남긴다")
        void shorterNativeAnswerProducesNegativeSavings() {
            TokenDelta delta = savingsCalculator.compareOutput(
                    "네.",
                    "Yes, that is correct and the deployment has already been completed successfully.");

            assertThat(delta.saved()).isNegative();
        }
    }
}
