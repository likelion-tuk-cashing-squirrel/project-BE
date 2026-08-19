package com.borderless.proxy.prompt;

import com.borderless.proxy.routing.RoutingTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SystemPromptBuilder")
class SystemPromptBuilderTest {

    /**
     * 실제 형식이다. GlossaryService가 UUID 앞 12자리를 대문자로 잘라 쓴다.
     * 문서와 주석에 흔히 적힌 {TERM_01}과 다르므로 테스트도 실제 형식으로 고정한다.
     */
    private static final String TOKEN_A = "{TERM_3F9A2B7C1D0E}";
    private static final String TOKEN_B = "{TERM_88AA11BB22CC}";

    private static final String HINT =
            "Tagalog morphology hints (root + affixes): nagsulat = nag + sulat";

    private final SystemPromptBuilder builder = new SystemPromptBuilder();

    @Nested
    @DisplayName("응답 언어 지시")
    class EnglishOnly {

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"TIER_2", "TIER_3"})
        @DisplayName("피벗 티어에는 영어로만 답하라고 지시한다")
        void pivotTiersGetEnglishInstruction(RoutingTier tier) {
            // 지시가 없으면 모델이 원어로 답할 수 있고, 그러면 재번역이 이중 번역이 되어
            // 출력 절감 측정이 같은 언어끼리 비교하게 된다.
            assertThat(builder.build(tier, Set.of(TOKEN_A))).contains("Respond in English only");
        }

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"SKIP", "TIER_1"})
        @DisplayName("피벗하지 않는 티어에는 영어 지시를 넣지 않는다")
        void nonPivotTiersOmitEnglishInstruction(RoutingTier tier) {
            assertThat(builder.build(tier, Set.of(TOKEN_A))).doesNotContain("English only");
        }
    }

    @Nested
    @DisplayName("마스킹 토큰 보존 지시")
    class PlaceholderRule {

        @Test
        @DisplayName("실제 토큰을 형식 설명 대신 그대로 나열한다")
        void listsActualTokens() {
            String prompt = builder.build(RoutingTier.TIER_1, List.of(TOKEN_A, TOKEN_B));

            assertThat(prompt).contains(TOKEN_A).contains(TOKEN_B);
            assertThat(prompt).contains("Do not translate, expand, explain, or reformat them");
        }

        @Test
        @DisplayName("치환된 토큰이 없으면 지시를 넣지 않는다")
        void noTokensMeansNoRule() {
            // 지킬 게 없는데 지시를 넣으면 입력 토큰만 낭비한다.
            String prompt = builder.build(RoutingTier.TIER_1, Set.of());

            assertThat(prompt).isEmpty();
        }

        @Test
        @DisplayName("토큰 목록이 null이어도 예외 없이 처리한다")
        void nullTokensAreTolerated() {
            assertThat(builder.build(RoutingTier.TIER_1, null)).isEmpty();
        }

        @Test
        @DisplayName("SKIP은 마스킹을 하지 않으므로 토큰이 넘어와도 무시한다")
        void skipTierIgnoresTokens() {
            // SKIP은 임계치 미만이라 마스킹 단계를 건너뛴다. 토큰이 있을 수 없다.
            assertThat(builder.build(RoutingTier.SKIP, Set.of(TOKEN_A))).isEmpty();
        }

        @Test
        @DisplayName("중복 토큰은 한 번만 넣는다")
        void deduplicatesTokens() {
            String prompt = builder.build(RoutingTier.TIER_1, List.of(TOKEN_A, TOKEN_A, TOKEN_A));

            assertThat(prompt.split(java.util.regex.Pattern.quote(TOKEN_A), -1)).hasSize(2);
        }

        @Test
        @DisplayName("공백이나 null 토큰은 걸러낸다")
        void filtersBlankTokens() {
            String prompt = builder.build(RoutingTier.TIER_1, java.util.Arrays.asList(null, "", "  "));

            assertThat(prompt).isEmpty();
        }
    }

    @Nested
    @DisplayName("형태소 힌트")
    class MorphologyHint {

        @Test
        @DisplayName("TIER_3에는 힌트를 붙인다")
        void tier3IncludesHint() {
            String prompt = builder.build(RoutingTier.TIER_3, Set.of(TOKEN_A), HINT);

            assertThat(prompt).contains("nagsulat = nag + sulat");
        }

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"SKIP", "TIER_1", "TIER_2"})
        @DisplayName("TIER_3가 아니면 힌트가 넘어와도 무시한다")
        void nonTier3IgnoresHint(RoutingTier tier) {
            // 티어 판정과 어긋난 힌트를 넣으면 모델이 없는 문법 구조를 가정한다.
            assertThat(builder.build(tier, Set.of(TOKEN_A), HINT)).doesNotContain("nagsulat");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\n"})
        @DisplayName("힌트가 비어 있으면 아무것도 붙이지 않는다")
        void blankHintIsOmitted(String hint) {
            String prompt = builder.build(RoutingTier.TIER_3, Set.of(), hint);

            assertThat(prompt).isEqualTo("Respond in English only, "
                    + "even if the request contains text in another language.");
        }

        @Test
        @DisplayName("힌트가 너무 길면 잘라낸다")
        void longHintIsTruncated() {
            String prompt = builder.build(RoutingTier.TIER_3, Set.of(), "x".repeat(5_000));

            // 프롬프트 비용이 무한히 커지는 걸 막는다.
            assertThat(prompt.length()).isLessThan(1_200);
        }
    }

    @Nested
    @DisplayName("전체 조립")
    class Composition {

        @Test
        @DisplayName("TIER_3는 영어 지시 + 토큰 보존 + 힌트를 모두 담는다")
        void tier3HasEverything() {
            String prompt = builder.build(RoutingTier.TIER_3, Set.of(TOKEN_A), HINT);

            assertThat(prompt.lines()).hasSize(3);
            assertThat(prompt).contains("Respond in English only")
                    .contains(TOKEN_A)
                    .contains("nagsulat");
        }

        @Test
        @DisplayName("SKIP은 지시할 게 없어 빈 문자열이다")
        void skipHasNothing() {
            // 빈 문자열이면 OpenAiChatRequest가 system 메시지를 아예 생략한다.
            assertThat(builder.build(RoutingTier.SKIP, Set.of(TOKEN_A), HINT)).isEmpty();
        }

        @Test
        @DisplayName("tier가 null이면 예외를 던진다")
        void rejectsNullTier() {
            assertThatThrownBy(() -> builder.build(null, Set.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tier");
        }
    }
}
