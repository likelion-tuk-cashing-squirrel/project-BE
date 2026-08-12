package com.borderless.proxy.proxy.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "usage_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "request_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private ProxyRequest request;

    @Column(nullable = false)
    private Integer inputTokens;

    @Column(nullable = false)
    private Integer outputTokens;

    @Column(nullable = false)
    private Integer savedTokens;

    @Column(nullable = false, precision = 10, scale = 6)
    private BigDecimal costUsd;

    @Column(nullable = false)
    private Integer latencyMs;

    @Column(nullable = false, length = 50)
    private String modelName;
}