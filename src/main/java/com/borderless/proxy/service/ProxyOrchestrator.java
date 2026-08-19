package com.borderless.proxy.service;

import com.borderless.proxy.billing.UsageRecorder;
import com.borderless.proxy.billing.dto.ReportedUsage;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.service.UsageSummaryService;
import com.borderless.proxy.client.LlmClient;
import com.borderless.proxy.client.TranslationClient;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.proxy.dto.ProxyResponseDTO;
import com.borderless.proxy.proxy.entity.UsageLog;
import com.borderless.proxy.routing.CostRouter;
import com.borderless.proxy.routing.RoutingTier;
import com.borderless.proxy.routing.dto.RoutingResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyOrchestrator {

    private final CostRouter costRouter;
    private final TermMasker termMasker;
    private final TermRestorer termRestorer;
    private final TranslationClient translationClient;
    private final LlmClient llmClient;
    private final UsageRecorder usageRecorder;
    private final UsageSummaryService usageSummaryService;

    public ProxyResponseDTO process(ProxyRequestDto request, Long memberId) {
        String originalText = request.getText();

        RoutingResult routing = costRouter.route(originalText);
        RoutingTier tier = routing.tier();

        String currentText = originalText;
        Map<String, String> dictionary = Map.of();

        // STEP 02: 마스킹
        if (tier.requiresMasking()) {
            MaskingResultDTO masked = termMasker.maskText(memberId, currentText);
            currentText = masked.getMaskedText();
            dictionary = masked.getDictionary();
        }

        // STEP 03: 영어 피벗
        if (tier.requiresPivot()) {
            TranslationRequest toEnglish = TranslationRequest.of(currentText, routing.detectedLanguage(), "EN");
            TranslationResponse translated = translationClient.translate(toEnglish).block();
            currentText = translated.getFirstText();
        }

        // billing용: 실제 LLM 전송 텍스트
        String sentTextToLlm = currentText;

        // STEP 04: LLM 호출
        LlmRequest llmRequest = LlmRequest.of(currentText);
        LlmResponse llmResponse = llmClient.complete(llmRequest).block();

        // billing용: 실제 LLM 응답(피벗 시 영어)
        String actualAnswerText = llmResponse.getContent();
        currentText = actualAnswerText;

        // STEP 05: 원어 재번역
        if (tier.requiresRetranslation()) {
            String targetLang = routing.detectedLanguage();
            TranslationRequest toOriginal = TranslationRequest.of(currentText, "EN", targetLang);
            TranslationResponse retranslated = translationClient.translate(toOriginal).block();
            currentText = retranslated.getFirstText();
        }

        // STEP 06: 마스킹 복원
        String finalResult = termRestorer.restoreText(currentText, dictionary);

        // billing 기록 (이번 요청 절약량 산출 + usage_log 저장)
        int savedTokens = 0;
        BigDecimal savedCostUsd = BigDecimal.ZERO;
        try {
            String nativeAnswerText = tier.requiresRetranslation() ? finalResult : null;

            UsageRecordRequest usageRequest = new UsageRecordRequest(
                    memberId,
                    originalText,
                    sentTextToLlm,
                    actualAnswerText,
                    nativeAnswerText,
                    routing.detectedLanguage(),
                    tier.name(),
                    tier.requiresPivot(),
                    llmResponse.getModel(),
                    new ReportedUsage(
                            llmResponse.getUsage().getPromptTokens(),
                            llmResponse.getUsage().getCompletionTokens()
                    ),
                    llmResponse.getLatencyMs()
            );

            UsageLog saved = usageRecorder.record(usageRequest);
            savedTokens = saved.getSavedTokens();
            savedCostUsd = saved.getSavedCostUsd();
        } catch (Exception e) {
            // 사용자 응답은 살리고, 과금 기록 실패만 로그로 남김
            log.warn("사용량/절감 기록 실패 memberId={}, reason={}", memberId, e.getMessage(), e);
        }

        // 누적 절약량 조회
        long cumulativeSavedTokens = usageSummaryService.getCumulativeSavedTokens(memberId);
        BigDecimal cumulativeSavedCostUsd = usageSummaryService.getCumulativeSavedCostUsd(memberId);

        return ProxyResponseDTO.builder()
                .result(finalResult)
                .usedTokens(llmResponse.getUsage().getTotalTokens())
                .pivoted(tier.requiresPivot())
                .savedTokens(savedTokens)
                .savedCostUsd(savedCostUsd)
                .cumulativeSavedTokens(cumulativeSavedTokens)
                .cumulativeSavedCostUsd(cumulativeSavedCostUsd)
                .build();
    }
}