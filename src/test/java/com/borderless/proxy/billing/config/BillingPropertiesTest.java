package com.borderless.proxy.billing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BillingProperties")
class BillingPropertiesTest {

    @Nested
    @DisplayName("설정 바인딩")
    class Binding {

        private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(BillingConfig.class);

        @Test
        @DisplayName("application.yaml의 수수료율과 모델 단가를 그대로 읽는다")
        void bindsFromApplicationYaml() {
            contextRunner.run(context -> {
                BillingProperties properties = context.getBean(BillingProperties.class);

                assertThat(properties.feeRate()).isEqualByComparingTo("0.10");

                ModelPricing mini = properties.pricingFor("gpt-4o-mini");
                assertThat(mini.inputPerMillion()).isEqualByComparingTo("0.15");
                assertThat(mini.outputPerMillion()).isEqualByComparingTo("0.60");

                // 대시보드 목업이 gpt-4o 기준으로 그려져 있어 함께 등록해 둔다.
                ModelPricing full = properties.pricingFor("gpt-4o");
                assertThat(full.inputPerMillion()).isEqualByComparingTo("2.50");
                assertThat(full.outputPerMillion()).isEqualByComparingTo("10.00");
            });
        }

        @Test
        @DisplayName("출력 단가가 입력 단가보다 높게 설정돼 있다")
        void outputCostsMoreThanInput() {
            contextRunner.run(context -> {
                BillingProperties properties = context.getBean(BillingProperties.class);

                // 절감 금액에서 출력이 더 큰 몫을 차지하는 근거다. 두 단가를 하나로 합치면 안 된다.
                properties.models().values().forEach(pricing ->
                        assertThat(pricing.outputPerMillion()).isGreaterThan(pricing.inputPerMillion()));
            });
        }

        @Configuration
        @EnableConfigurationProperties(BillingProperties.class)
        static class BillingConfig {
        }
    }

    @Nested
    @DisplayName("단가 조회")
    class PricingLookup {

        private final BillingProperties properties = new BillingProperties(
                new BigDecimal("0.10"),
                Map.of("GPT-4o-Mini", new ModelPricing(new BigDecimal("0.15"), new BigDecimal("0.60"))));

        @ParameterizedTest
        @ValueSource(strings = {"GPT-4o-Mini", "gpt-4o-mini", "GPT-4O-MINI", "  gpt-4o-mini  "})
        @DisplayName("모델 이름의 대소문자와 앞뒤 공백은 무시한다")
        void lookupIsCaseInsensitive(String modelName) {
            assertThat(properties.pricingFor(modelName).inputPerMillion()).isEqualByComparingTo("0.15");
        }

        @Test
        @DisplayName("models 블록이 없어도 생성되고, 조회 시점에 예외로 알린다")
        void missingModelsMapIsEmpty() {
            BillingProperties empty = new BillingProperties(new BigDecimal("0.10"), null);

            assertThat(empty.models()).isEmpty();
            assertThatThrownBy(() -> empty.pricingFor("gpt-4o-mini"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("billing.models");
        }

        @ParameterizedTest
        @ValueSource(strings = {"gpt-4o-mini-2024-07-18", "GPT-4o-Mini-2025-04-14"})
        @DisplayName("스냅샷 날짜가 붙은 이름도 별칭 단가로 조회한다")
        void lookupStripsSnapshotSuffix(String snapshotName) {
            // OpenAI는 별칭으로 요청해도 응답 model 필드에 스냅샷 이름을 돌려준다.
            // 이걸 못 찾으면 모든 요청의 비용 계산이 실패한다.
            assertThat(properties.pricingFor(snapshotName).inputPerMillion()).isEqualByComparingTo("0.15");
        }

        @Test
        @DisplayName("등록되지 않은 모델은 스냅샷을 떼도 찾을 수 없으면 예외로 알린다")
        void unknownModelStillThrowsAfterStripping() {
            // 기본 단가로 조용히 폴백하면 틀린 금액이 로그 없이 정산에 쌓인다.
            assertThatThrownBy(() -> properties.pricingFor("gpt-5-nano-2025-08-07"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("gpt-5-nano-2025-08-07");
        }

        @Test
        @DisplayName("날짜 형식이 아닌 접미사는 떼지 않는다")
        void doesNotStripNonDateSuffix() {
            // gpt-4o-mini-search-preview는 별개 모델이고 단가도 다르다. 별칭으로 뭉개면 안 된다.
            assertThatThrownBy(() -> properties.pricingFor("gpt-4o-mini-search-preview"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("값 검증")
    class Validation {

        @ParameterizedTest
        @ValueSource(strings = {"-0.01", "1.01"})
        @DisplayName("수수료율이 0.0 ~ 1.0 범위를 벗어나면 yaml 키 이름과 함께 예외를 던진다")
        void rejectsFeeRateOutOfRange(String feeRate) {
            assertThatThrownBy(() -> new BillingProperties(new BigDecimal(feeRate), Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("billing.fee-rate")
                    .hasMessageContaining(feeRate);
        }

        @Test
        @DisplayName("음수 단가는 거부한다")
        void rejectsNegativePricing() {
            assertThatThrownBy(() -> new ModelPricing(new BigDecimal("-0.15"), new BigDecimal("0.60")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("input-per-million");
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.0, 1.0})
        @DisplayName("수수료율 0%와 100%는 경계값으로 허용한다")
        void allowsFeeRateBoundaries(double feeRate) {
            assertThat(new BillingProperties(BigDecimal.valueOf(feeRate), Map.of()).feeRate())
                    .isEqualByComparingTo(BigDecimal.valueOf(feeRate));
        }
    }
}
