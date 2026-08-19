package com.borderless.proxy.proxy.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 요청 한 건의 토큰 사용량과 비용 기록.
 *
 * <p>금액 컬럼은 모두 소수점 6자리로 맞춘다. {@code CostCalculator}가 같은 scale로 계산하지만,
 * 호출부가 다른 scale의 값을 넘겨도 DB에서 조용히 잘리지 않도록 이 클래스에서 한 번 더 정규화한다.
 *
 * <p>이 엔티티는 과금 계산 결과만 받아 담는다. {@code billing} 패키지에 의존하지 않도록
 * 빌더가 원시값과 {@link BigDecimal}만 받는다. 조립은 {@code UsageRecorder}가 담당한다.
 */
@Entity
@Table(name = "usage_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageLog {

    /** 금액 컬럼의 공통 scale. {@code CostCalculator}의 USD_SCALE과 같아야 한다. */
    private static final int USD_SCALE = 6;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private ProxyRequest request;

    /** API가 보고한 입력 토큰 수. 로컬 추정치가 아니라 실측값이다. */
    @Column(nullable = false)
    private Integer inputTokens;

    /** API가 보고한 출력 토큰 수. */
    @Column(nullable = false)
    private Integer outputTokens;

    /**
     * 마스킹·피벗으로 절약한 토큰 수. 파이프라인이 역효과를 냈으면 음수가 될 수 있다.
     * 음수를 0으로 깎지 않는 이유는 임계치·용어집 튜닝의 근거가 되기 때문이다.
     */
    @Column(nullable = false)
    private Integer savedTokens;

    /** 실제로 발생한 원가(USD). API 보고 사용량 × 모델 단가. 수수료는 포함하지 않는다. */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    /** 절약한 금액(USD). 프록시를 안 썼을 때 대비 회피한 비용. 음수 가능. */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal savedCostUsd;

    /** 절약분에 수수료율을 적용한 청구 수수료(USD). 절약분이 0 이하면 0이다. */
    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal feeUsd;

    /** 외부 API 호출 왕복 시간(ms). */
    @Column(nullable = false)
    private Integer latencyMs;

    /** 원가 산정에 사용한 모델 이름. */
    @Column(nullable = false, length = 50)
    private String modelName;

    @Builder
    private UsageLog(ProxyRequest request,
                     Integer inputTokens,
                     Integer outputTokens,
                     Integer savedTokens,
                     BigDecimal costUsd,
                     BigDecimal savedCostUsd,
                     BigDecimal feeUsd,
                     Integer latencyMs,
                     String modelName) {

        this.request = require(request, "request");
        this.inputTokens = requireNotNegative(inputTokens, "inputTokens");
        this.outputTokens = requireNotNegative(outputTokens, "outputTokens");
        // savedTokens는 음수를 허용한다.
        this.savedTokens = require(savedTokens, "savedTokens");
        this.costUsd = scaled(costUsd, "costUsd");
        this.savedCostUsd = scaled(savedCostUsd, "savedCostUsd");
        this.feeUsd = scaled(feeUsd, "feeUsd");
        this.latencyMs = requireNotNegative(latencyMs, "latencyMs");
        this.modelName = require(modelName, "modelName");
    }

    /** 원가 + 수수료. 실제로 사용자에게 청구할 금액이다. */
    public BigDecimal billedUsd() {
        return costUsd.add(feeUsd);
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "은 null일 수 없습니다.");
        }
        return value;
    }

    private static Integer requireNotNegative(Integer value, String field) {
        if (require(value, field) < 0) {
            throw new IllegalArgumentException("%s는 음수일 수 없습니다: %d".formatted(field, value));
        }
        return value;
    }

    private static BigDecimal scaled(BigDecimal value, String field) {
        return require(value, field).setScale(USD_SCALE, RoundingMode.HALF_UP);
    }
}
