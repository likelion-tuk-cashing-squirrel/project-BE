package com.borderless.proxy.service;

import com.borderless.proxy.client.LlmClient;
import com.borderless.proxy.client.TranslationClient;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.dto.ProxyResponseDto;
import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.routing.RoutingTier;
import com.borderless.proxy.routing.dto.RoutingResult;
import com.borderless.proxy.routing.CostRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProxyOrchestrator {

    // 셰프가 부릴 담당자들 (스프링이 자동으로 넣어줌)
    private final CostRouter costRouter;             // 라우팅 판별
    private final TermMasker termMasker;             // 마스킹
    private final TermRestorer termRestorer;         // 복원
    private final TranslationClient translationClient; // 번역
    private final LlmClient llmClient;               // LLM 호출

    // 손님 주문(요청)이 들어오면 이 함수가 전체 흐름을 지휘한다
    public ProxyResponseDto process(ProxyRequestDto request, Long memberId) {

        // 손님이 입력한 원본 텍스트
        String originalText = request.getText();

        // ===== 1. 라우팅 판별 =====
        // 텍스트를 보고 어떤 처리 경로(티어)로 갈지 결정한다
        RoutingResult routing = costRouter.route(originalText);
        RoutingTier tier = routing.tier();

        // 파이프라인을 거치며 텍스트가 계속 바뀐다. 처음엔 원본으로 시작.
        String currentText = originalText;
        // 복원할 때 쓸 사전 (마스킹을 안 하면 비어 있음)
        java.util.Map<String, String> dictionary = java.util.Map.of();

        // ===== 2. 마스킹 =====
        // 이 티어가 마스킹이 필요할 때만 수행 (고유명사를 토큰으로 가림)
        if (tier.requiresMasking()) {
            MaskingResultDTO masked = termMasker.maskText(memberId, currentText);
            currentText = masked.getMaskedText();
            dictionary = masked.getDictionary();
        }

        // ===== 3. 피벗 번역 (원어 -> 영어) =====
        // 피벗이 필요한 티어(베트남어/타갈로그 등)일 때만 영어로 번역
        if (tier.requiresPivot()) {
            TranslationRequest toEnglish = TranslationRequest.of(currentText, "EN");
            TranslationResponse translated = translationClient.translate(toEnglish).block();
            currentText = translated.getFirstText();
        }

        // ===== 4. LLM 호출 =====
        // 영어로 변환된(또는 원래 영어인) 텍스트를 LLM에 넣는다
        LlmRequest llmRequest = LlmRequest.of(currentText);
        LlmResponse llmResponse = llmClient.complete(llmRequest).block();
        currentText = llmResponse.getContent();
        int usedTokens = llmResponse.getUsage().getTotalTokens();

        // ===== 5. 원어 재번역 (영어 -> 원어) =====
        // 피벗했던 경우에만, LLM 영어 답변을 다시 원래 언어로 되돌린다
        if (tier.requiresRetranslation()) {
            String targetLang = routing.detectedLanguage();
            TranslationRequest toOriginal = TranslationRequest.of(currentText, targetLang);
            TranslationResponse retranslated = translationClient.translate(toOriginal).block();
            currentText = retranslated.getFirstText();
        }

        // ===== 6. 마스킹 복원 =====
        // 가렸던 토큰을 다시 원래 단어로 되돌린다
        String finalResult = termRestorer.restoreText(currentText, dictionary);

        // ===== 최종 응답 만들기 =====
        return ProxyResponseDto.builder()
                .result(finalResult)              // 최종 결과 텍스트
                .usedTokens(usedTokens)           // 실제 토큰 수
                .pivoted(tier.requiresPivot())    // 실제 피벗 여부
                .build();
    }
}