package com.borderless.proxy.billing.service;

import com.borderless.proxy.billing.repository.UsageLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class UsageSummaryService {

    private final UsageLogRepository usageLogRepository;

    public long getCumulativeSavedTokens(Long memberId) {
        return usageLogRepository.sumSavedTokensByMemberId(memberId);
    }

    public BigDecimal getCumulativeSavedCostUsd(Long memberId) {
        return usageLogRepository.sumSavedCostUsdByMemberId(memberId);
    }
}