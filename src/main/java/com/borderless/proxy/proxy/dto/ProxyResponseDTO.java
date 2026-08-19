package com.borderless.proxy.proxy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ProxyResponseDTO {

    private String result;
    private int usedTokens;
    private boolean pivoted;

    // 이번 요청 절약 값
    private int savedTokens;
    private BigDecimal savedCostUsd;

    // 누적 절약 값
    private long cumulativeSavedTokens;
    private BigDecimal cumulativeSavedCostUsd;
}
