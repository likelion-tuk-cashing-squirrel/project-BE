package com.borderless.proxy.service;

import com.borderless.proxy.billing.UsageRecorder;
import com.borderless.proxy.billing.dto.ReportedUsage;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.service.UsageSummaryService;
import com.borderless.proxy.client.LlmClient;
import com.borderless.proxy.client.MorphologyClient;
import com.borderless.proxy.client.TranslationClient;
import com.borderless.proxy.client.config.MorphologyProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.MorphologyHints;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.prompt.SystemPromptBuilder;
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
    private final MorphologyClient morphologyClient;
    private final MorphologyProperties morphologyProperties;
    private final LlmClient llmClient;
    private final SystemPromptBuilder systemPromptBuilder;
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

        // STEP 03-B: 형태소 힌트 (TIER_3만)
        //
        // 피벗보다 먼저 호출한다. 사이드카는 타갈로그를 분석하므로 영어로 번역된 뒤에
        // 넘기면 어근·접사를 찾을 수 없다. 단계 번호가 03-B라 순서를 오해하기 쉬운 지점이다.
        //
        // 마스킹된 텍스트를 넘긴다. 사이드카가 {TERM_...} 토큰을 분석 대상에서 제외하므로
        // 치환 토큰이 소문자로 쪼개져 힌트에 섞이는 일이 없다.
        //
        // 실패는 클라이언트가 삼킨다(app.morphology.optional=true). 힌트가 없으면 빈 문자열이
        // 되고 시스템 프롬프트에 아무것도 붙지 않는다.
        //
        // app.morphology.inject-hint가 꺼져 있으면 호출 자체를 생략한다. 힌트의 유일한 소비처가
        // 시스템 프롬프트라, 쓰지 않을 값을 위해 사이드카 왕복 시간을 쓸 이유가 없다.
        // 기본값이 꺼짐인 근거는 MorphologyProperties.injectHint 주석에 있다.
        String morphologyHint = "";
        if (tier.requiresMorphologyHint() && morphologyProperties.isInjectHint()) {
            MorphologyHints hints = morphologyClient.hints(currentText).block();
            if (hints != null) {
                morphologyHint = hints.getHint();
            }
            log.debug("형태소 힌트 조회 완료: tier={}, 힌트 길이={}", tier, morphologyHint.length());
        }

        // STEP 03: 영어 피벗
        if (tier.requiresPivot()) {
            TranslationRequest toEnglish = TranslationRequest.of(currentText, routing.detectedLanguage(), "EN");
            TranslationResponse translated = translationClient.translate(toEnglish).block();
            currentText = translated.getFirstText();
        }

        // STEP 04: LLM 호출
        //
        // 시스템 프롬프트는 마스킹 사전의 키(= 실제로 치환된 토큰)를 넘겨서 만든다.
        // 치환된 게 없으면 빌더가 빈 문자열을 돌려주고, OpenAiChatRequest가 system 메시지를
        // 아예 생략한다. 지킬 게 없는데 지시를 붙이면 입력 토큰만 늘어 절감 목적에 역행한다.
        String systemPrompt = systemPromptBuilder.build(tier, dictionary.keySet(), morphologyHint);

        // billing용: 실제 LLM 전송 텍스트.
        //
        // 시스템 프롬프트를 포함시킨다. CostCalculator 주석은 전후 비교에 "페이로드 텍스트만"
        // 세라고 하지만, 그건 양쪽에 똑같이 실리는 오버헤드를 상쇄하려는 취지다.
        // 시스템 프롬프트는 프록시를 써서 생긴 비용이라 기준값(원문 직접 전송)에는 없다.
        // 빼고 세면 프록시가 얹은 비용이 지표에서 사라져 절감분이 과대 계상된다.
        String sentTextToLlm = systemPrompt.isEmpty() ? currentText : systemPrompt + "\n" + currentText;

        LlmRequest llmRequest = LlmRequest.of(systemPrompt, currentText);
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