package com.borderless.proxy.controller;

import com.borderless.proxy.billing.service.UsageSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class UsageSummaryController {

    private final UsageSummaryService usageSummaryService;

    @GetMapping("/summary")
    public Map<String, Object> summary(@AuthenticationPrincipal OAuth2User principal) {
        Long memberId = principal.getAttribute("memberId");

        long cumulativeSavedTokens = usageSummaryService.getCumulativeSavedTokens(memberId);
        BigDecimal cumulativeSavedCostUsd = usageSummaryService.getCumulativeSavedCostUsd(memberId);

        return Map.of(
                "cumulativeSavedTokens", cumulativeSavedTokens,
                "cumulativeSavedCostUsd", cumulativeSavedCostUsd
        );
    }
}