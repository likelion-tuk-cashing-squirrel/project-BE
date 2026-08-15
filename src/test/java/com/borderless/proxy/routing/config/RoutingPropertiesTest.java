package com.borderless.proxy.routing.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RoutingProperties")
class RoutingPropertiesTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("설정을 생략하면 기본 임계치가 적용된다")
    void appliesDefaultsWhenAbsent() {
        contextRunner.run(context -> {
            RoutingProperties properties = context.getBean(RoutingProperties.class);

            assertThat(properties.tokenThreshold()).isEqualTo(50);
            assertThat(properties.confidenceThreshold()).isEqualTo(0.7);
        });
    }

    @Test
    @DisplayName("application 설정값으로 임계치를 덮어쓸 수 있다")
    void bindsFromConfiguration() {
        contextRunner
                .withPropertyValues("routing.token-threshold=120", "routing.confidence-threshold=0.85")
                .run(context -> {
                    RoutingProperties properties = context.getBean(RoutingProperties.class);

                    assertThat(properties.tokenThreshold()).isEqualTo(120);
                    assertThat(properties.confidenceThreshold()).isEqualTo(0.85);
                });
    }

    @Test
    @DisplayName("음수 토큰 임계치는 거부한다")
    void rejectsNegativeTokenThreshold() {
        assertThatThrownBy(() -> new RoutingProperties(-1, 0.7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("token-threshold");
    }

    @Test
    @DisplayName("0.0 ~ 1.0을 벗어난 신뢰도 임계치는 거부한다")
    void rejectsConfidenceThresholdOutOfRange() {
        assertThatThrownBy(() -> new RoutingProperties(50, -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence-threshold");
        assertThatThrownBy(() -> new RoutingProperties(50, 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence-threshold");
    }

    @EnableConfigurationProperties(RoutingProperties.class)
    static class TestConfig {
    }
}
