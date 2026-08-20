package com.borderless.proxy.service;

import com.borderless.proxy.billing.UsageRecorder;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.service.UsageSummaryService;
import com.borderless.proxy.client.LlmClient;
import com.borderless.proxy.client.MorphologyClient;
import com.borderless.proxy.client.TranslationClient;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.MorphologyHints;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.prompt.SystemPromptBuilder;
import com.borderless.proxy.proxy.dto.ProxyResponseDTO;
import com.borderless.proxy.proxy.entity.UsageLog;
import com.borderless.proxy.routing.CostRouter;
import com.borderless.proxy.routing.RoutingTier;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 파이프라인 전체를 조립하는 유일한 지점의 분기 검증.
 *
 * <p>티어마다 수행 단계가 달라지는데(마스킹·형태소 힌트·피벗·재번역) 이 분기는 회귀에 취약하다.
 * 실제로 PR #44에서 컨트롤러와 오케스트레이터의 인자 불일치로 컴파일이 깨진 채 올라온 적이 있다(#45).
 *
 * <p><b>순수 로직 컴포넌트는 실제 객체를 쓴다.</b> {@link TermRestorer}와
 * {@link SystemPromptBuilder}는 외부 I/O가 없어서 목으로 대체하면 배선만 확인되고
 * 복원·프롬프트 조립이 실제로 맞물리는지는 검증되지 않는다.
 *
 * <p>{@link CostRouter}는 목으로 둔다. 티어를 문장 길이나 언어 감지 결과로 유도하면
 * 라우팅 튜닝(임계치·신뢰도) 때문에 이 테스트가 같이 깨진다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProxyOrchestrator")
class ProxyOrchestratorTest {

    private static final Long MEMBER_ID = 7L;

    private static final String ORIGINAL_TL = "Nagsulat ako ng liham para sa Proyekto Sigasig kahapon.";

    /**
     * 실제 마스킹 토큰 형식이다. GlossaryService가 UUID 앞 12자리를 대문자로 잘라 쓴다.
     * 문서에 흔히 적힌 {TERM_01}과 다르므로 테스트도 실제 형식으로 고정한다.
     */
    private static final String TOKEN = "{TERM_3F9A2B7C1D0E}";

    private static final String MASKED_TL = "Nagsulat ako ng liham para sa " + TOKEN + " kahapon.";
    private static final Map<String, String> DICTIONARY = Map.of(TOKEN, "Project Sigasig");

    private static final String PIVOTED_EN = "I wrote a letter for " + TOKEN + " yesterday.";
    private static final String LLM_ANSWER_EN = "Understood. " + TOKEN + " received the letter.";
    private static final String RETRANSLATED_TL = "Naiintindihan. Natanggap ng " + TOKEN + " ang liham.";

    private static final String MORPHOLOGY_HINT =
            "Tagalog morphology hints (root + affixes), use them to preserve tense and aspect: "
                    + "Nagsulat = nag + sulat";

    private static final String MODEL = "gpt-4.1-mini";

    @Mock
    private CostRouter costRouter;

    @Mock
    private TermMasker termMasker;

    @Mock
    private TranslationClient translationClient;

    @Mock
    private MorphologyClient morphologyClient;

    @Mock
    private LlmClient llmClient;

    @Mock
    private UsageRecorder usageRecorder;

    @Mock
    private UsageSummaryService usageSummaryService;

    private ProxyOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ProxyOrchestrator(
                costRouter,
                termMasker,
                new TermRestorer(),
                translationClient,
                morphologyClient,
                llmClient,
                new SystemPromptBuilder(),
                usageRecorder,
                usageSummaryService);

        given(termMasker.maskText(eq(MEMBER_ID), anyString()))
                .willReturn(new MaskingResultDTO(MASKED_TL, DICTIONARY));

        // 피벗과 재번역을 도착 언어로 구분한다. 목이 호출 순서에 의존하면
        // 순서가 바뀌었을 때 실패 원인이 드러나지 않는다.
        given(translationClient.translate(any())).willAnswer(call -> {
            TranslationRequest request = call.getArgument(0);
            return Mono.just(translationResponse(
                    "EN".equals(request.getTargetLang()) ? PIVOTED_EN : RETRANSLATED_TL));
        });

        given(morphologyClient.hints(anyString()))
                .willReturn(Mono.just(MorphologyHints.builder()
                        .text(MASKED_TL)
                        .hint(MORPHOLOGY_HINT)
                        .build()));

        given(llmClient.complete(any())).willReturn(Mono.just(LlmResponse.builder()
                .content(LLM_ANSWER_EN)
                .model(MODEL)
                .usage(LlmResponse.TokenUsage.builder()
                        .promptTokens(120).completionTokens(30).totalTokens(150)
                        .build())
                .finishReason("stop")
                .latencyMs(842)
                .build()));

        // 목을 given(...) 인자 안에서 만들면 Mockito가 중첩 스터빙으로 보고 거부한다.
        UsageLog savedLog = usageLog(42, "0.001234");
        given(usageRecorder.record(any())).willReturn(savedLog);

        given(usageSummaryService.getCumulativeSavedTokens(anyLong())).willReturn(9_000L);
        given(usageSummaryService.getCumulativeSavedCostUsd(anyLong()))
                .willReturn(new BigDecimal("0.250000"));
    }

    private ProxyResponseDTO process(RoutingTier tier, String language) {
        given(costRouter.route(anyString()))
                .willReturn(new RoutingResult(tier, language, 120, 0.95));

        return orchestrator.process(request(ORIGINAL_TL), MEMBER_ID);
    }

    @Nested
    @DisplayName("티어별 수행 단계")
    class TierBranching {

        @Test
        @DisplayName("SKIP은 마스킹·형태소·번역을 모두 건너뛰고 LLM만 호출한다")
        void skipCallsLlmOnly() {
            process(RoutingTier.SKIP, RoutingResult.UNDETERMINED_LANGUAGE);

            verify(termMasker, never()).maskText(any(), anyString());
            verify(morphologyClient, never()).hints(anyString());
            verify(translationClient, never()).translate(any());
            verify(llmClient).complete(any());
        }

        @Test
        @DisplayName("TIER_1은 마스킹만 하고 번역은 호출하지 않는다")
        void tier1MasksWithoutTranslation() {
            process(RoutingTier.TIER_1, "en");

            verify(termMasker).maskText(MEMBER_ID, ORIGINAL_TL);
            verify(translationClient, never()).translate(any());
            verify(morphologyClient, never()).hints(anyString());
        }

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"TIER_2", "TIER_3"})
        @DisplayName("피벗 티어는 번역을 2회 호출한다")
        void pivotTiersTranslateTwice(RoutingTier tier) {
            process(tier, tier == RoutingTier.TIER_3 ? "tl" : "vi");

            // 피벗 1회 + 재번역 1회. 한쪽이 빠지면 원어 답변이 나오지 않는다.
            verify(translationClient, times(2)).translate(any());
        }

        @Test
        @DisplayName("TIER_3만 형태소 사이드카를 호출한다")
        void onlyTier3CallsMorphologySidecar() {
            process(RoutingTier.TIER_3, "tl");

            verify(morphologyClient).hints(anyString());
        }

        @Test
        @DisplayName("TIER_2는 형태소 사이드카를 호출하지 않는다")
        void tier2SkipsMorphologySidecar() {
            // 타갈로그가 아닌 언어를 타갈로그 스테머에 넣으면 엉뚱한 어근이 나온다.
            process(RoutingTier.TIER_2, "vi");

            verify(morphologyClient, never()).hints(anyString());
        }
    }

    @Nested
    @DisplayName("번역 단계 인자")
    class TranslationArguments {

        @Test
        @DisplayName("피벗은 감지된 언어에서 영어로, 재번역은 영어에서 감지된 언어로 보낸다")
        void usesDetectedLanguageOnBothLegs() {
            process(RoutingTier.TIER_2, "vi");

            List<TranslationRequest> requests = capturedTranslations();

            // 재번역 도착 언어를 고정값으로 박으면 베트남어 사용자에게 다른 언어가 돌아간다.
            assertThat(requests.get(0).getSourceLang()).isEqualTo("VI");
            assertThat(requests.get(0).getTargetLang()).isEqualTo("EN");
            assertThat(requests.get(1).getSourceLang()).isEqualTo("EN");
            assertThat(requests.get(1).getTargetLang()).isEqualTo("VI");
        }

        @Test
        @DisplayName("피벗에는 원문이 아니라 마스킹된 텍스트를 보낸다")
        void pivotSendsMaskedText() {
            process(RoutingTier.TIER_2, "vi");

            assertThat(capturedTranslations().get(0).getTexts()).containsExactly(MASKED_TL);
        }

        @Test
        @DisplayName("재번역에는 LLM 답변을 보낸다")
        void retranslationSendsLlmAnswer() {
            process(RoutingTier.TIER_2, "vi");

            assertThat(capturedTranslations().get(1).getTexts()).containsExactly(LLM_ANSWER_EN);
        }
    }

    @Nested
    @DisplayName("형태소 힌트 (STEP 03-B)")
    class MorphologyStep {

        @Test
        @DisplayName("피벗 전에 마스킹된 원어 텍스트로 호출한다")
        void calledBeforePivotWithMaskedNativeText() {
            process(RoutingTier.TIER_3, "tl");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(morphologyClient).hints(captor.capture());

            // 사이드카는 타갈로그를 분석한다. 피벗 뒤에 호출하면 영어 문장이 넘어가
            // 어근·접사를 하나도 찾지 못한다. 이 단정이 호출 순서를 고정한다.
            assertThat(captor.getValue()).isEqualTo(MASKED_TL);
            assertThat(captor.getValue()).isNotEqualTo(PIVOTED_EN);
        }

        @Test
        @DisplayName("조회한 힌트를 시스템 프롬프트에 싣는다")
        void hintReachesSystemPrompt() {
            process(RoutingTier.TIER_3, "tl");

            assertThat(capturedLlmRequest().getSystemPrompt()).contains("Nagsulat = nag + sulat");
        }

        @Test
        @DisplayName("사이드카가 빈 힌트를 주면 프롬프트에 아무것도 붙지 않는다")
        void emptyHintAddsNothing() {
            // 사이드카 장애 시 클라이언트가 MorphologyHints.empty()로 대체한다.
            given(morphologyClient.hints(anyString()))
                    .willReturn(Mono.just(MorphologyHints.empty(MASKED_TL)));

            process(RoutingTier.TIER_3, "tl");

            assertThat(capturedLlmRequest().getSystemPrompt()).doesNotContain("morphology hints");
        }

        @Test
        @DisplayName("사이드카가 빈 Mono를 주더라도 파이프라인이 진행된다")
        void emptyMonoDoesNotBreakPipeline() {
            given(morphologyClient.hints(anyString())).willReturn(Mono.empty());

            ProxyResponseDTO response = process(RoutingTier.TIER_3, "tl");

            assertThat(response.getResult()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("시스템 프롬프트 (STEP 04)")
    class SystemPrompt {

        @Test
        @DisplayName("피벗 티어에는 영어 응답 지시를 담는다")
        void pivotTierGetsEnglishInstruction() {
            process(RoutingTier.TIER_2, "vi");

            // 지시가 없으면 모델이 원어로 답해 재번역이 이중 번역이 되고 출력 절감 지표가 망가진다.
            assertThat(capturedLlmRequest().getSystemPrompt()).contains("Respond in English only");
        }

        @Test
        @DisplayName("치환된 토큰을 그대로 나열해 보존을 지시한다")
        void listsMaskedTokens() {
            process(RoutingTier.TIER_1, "en");

            assertThat(capturedLlmRequest().getSystemPrompt()).contains(TOKEN);
        }

        @Test
        @DisplayName("SKIP은 지시할 게 없어 시스템 프롬프트가 비어 있다")
        void skipHasEmptySystemPrompt() {
            process(RoutingTier.SKIP, RoutingResult.UNDETERMINED_LANGUAGE);

            // 빈 문자열이면 OpenAiChatRequest가 system 메시지를 아예 생략한다.
            assertThat(capturedLlmRequest().getSystemPrompt()).isEmpty();
        }

        @Test
        @DisplayName("사용자 프롬프트에는 시스템 프롬프트를 섞지 않는다")
        void userPromptStaysClean() {
            process(RoutingTier.TIER_2, "vi");

            assertThat(capturedLlmRequest().getUserPrompt()).isEqualTo(PIVOTED_EN);
        }
    }

    @Nested
    @DisplayName("마스킹 복원 (STEP 06)")
    class Restoration {

        @Test
        @DisplayName("마스킹 사전이 복원 단계까지 전달돼 토큰이 원래 용어로 바뀐다")
        void dictionaryReachesRestorer() {
            ProxyResponseDTO response = process(RoutingTier.TIER_2, "vi");

            // 사전이 유실되면 사용자 화면에 {TERM_...}이 그대로 노출된다.
            assertThat(response.getResult()).contains("Project Sigasig");
            assertThat(response.getResult()).doesNotContain(TOKEN);
        }

        @Test
        @DisplayName("SKIP은 마스킹을 하지 않으므로 LLM 답변이 그대로 나온다")
        void skipReturnsLlmAnswerAsIs() {
            ProxyResponseDTO response = process(RoutingTier.SKIP, RoutingResult.UNDETERMINED_LANGUAGE);

            assertThat(response.getResult()).isEqualTo(LLM_ANSWER_EN);
        }
    }

    @Nested
    @DisplayName("응답 값")
    class Response {

        @Test
        @DisplayName("usedTokens는 LLM이 보고한 총 토큰 수다")
        void usedTokensComesFromLlmUsage() {
            assertThat(process(RoutingTier.TIER_2, "vi").getUsedTokens()).isEqualTo(150);
        }

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"TIER_2", "TIER_3"})
        @DisplayName("피벗 티어는 pivoted가 true다")
        void pivotTiersReportPivoted(RoutingTier tier) {
            assertThat(process(tier, "vi").isPivoted()).isTrue();
        }

        @ParameterizedTest
        @EnumSource(value = RoutingTier.class, names = {"SKIP", "TIER_1"})
        @DisplayName("피벗하지 않는 티어는 pivoted가 false다")
        void nonPivotTiersReportNotPivoted(RoutingTier tier) {
            assertThat(process(tier, "en").isPivoted()).isFalse();
        }

        @Test
        @DisplayName("이번 요청 절약분과 누적 절약분을 함께 담는다")
        void carriesSavingsAndCumulative() {
            ProxyResponseDTO response = process(RoutingTier.TIER_2, "vi");

            assertThat(response.getSavedTokens()).isEqualTo(42);
            assertThat(response.getSavedCostUsd()).isEqualByComparingTo("0.001234");
            assertThat(response.getCumulativeSavedTokens()).isEqualTo(9_000L);
            assertThat(response.getCumulativeSavedCostUsd()).isEqualByComparingTo("0.250000");
        }
    }

    @Nested
    @DisplayName("과금 기록")
    class Billing {

        @Test
        @DisplayName("전송문에 시스템 프롬프트를 포함시킨다")
        void sentTextIncludesSystemPrompt() {
            process(RoutingTier.TIER_2, "vi");

            UsageRecordRequest recorded = capturedUsageRecord();

            // 시스템 프롬프트는 프록시를 써서 생긴 입력 비용이다. 기준값(원문 직접 전송)에는
            // 없으므로 빼고 세면 절감분이 과대 계상된다.
            assertThat(recorded.sentText()).contains("Respond in English only");
            assertThat(recorded.sentText()).contains(PIVOTED_EN);
        }

        @Test
        @DisplayName("재번역을 탄 흐름은 원어 답변을 기준값으로 넘긴다")
        void pivotedFlowPassesNativeAnswer() {
            process(RoutingTier.TIER_2, "vi");

            UsageRecordRequest recorded = capturedUsageRecord();

            assertThat(recorded.actualAnswerText()).isEqualTo(LLM_ANSWER_EN);
            assertThat(recorded.nativeAnswerText()).contains("Project Sigasig");
            assertThat(recorded.pivoted()).isTrue();
            assertThat(recorded.routeDecision()).isEqualTo("TIER_2");
        }

        @Test
        @DisplayName("재번역을 타지 않은 흐름은 원어 답변을 null로 넘긴다")
        void nonPivotedFlowPassesNullNativeAnswer() {
            process(RoutingTier.TIER_1, "en");

            // null이어야 TokenSavingsCalculator가 출력 절감을 0으로 둔다.
            // 값을 넣으면 같은 영어끼리 비교해 절감이 0에 가깝게 왜곡된다.
            assertThat(capturedUsageRecord().nativeAnswerText()).isNull();
        }

        @Test
        @DisplayName("기록이 실패해도 사용자 응답은 그대로 반환한다")
        void recordingFailureDoesNotBreakResponse() {
            // 단가가 없는 모델이면 UsageRecorder가 IllegalArgumentException을 던진다.
            // 그걸 흘리면 답변까지 나온 요청이 500으로 죽는다.
            given(usageRecorder.record(any()))
                    .willThrow(new IllegalArgumentException("단가가 등록되지 않은 모델입니다."));

            ProxyResponseDTO response = process(RoutingTier.TIER_2, "vi");

            assertThat(response.getResult()).contains("Project Sigasig");
            assertThat(response.getSavedTokens()).isZero();
            assertThat(response.getSavedCostUsd()).isEqualByComparingTo("0");
        }
    }

    // ---------------------------------------------------------------------
    // 헬퍼
    // ---------------------------------------------------------------------

    private static ProxyRequestDto request(String text) {
        ProxyRequestDto dto = new ProxyRequestDto();
        setText(dto, text);
        return dto;
    }

    /**
     * {@code ProxyRequestDto}는 기본 생성자와 게터만 노출한다. 컨트롤러에서 Jackson이 채우는
     * DTO라 세터가 없어, 테스트에서 값을 넣으려면 리플렉션이 필요하다.
     *
     * <p>프로덕션 코드에 테스트 전용 생성자를 추가하는 대신 여기서 감당한다. 요청 DTO는
     * 역직렬화 대상이라 생성 경로를 늘리면 컨트롤러 계층 규약이 흐려진다.
     */
    private static void setText(ProxyRequestDto dto, String text) {
        try {
            Field field = ProxyRequestDto.class.getDeclaredField("text");
            field.setAccessible(true);
            field.set(dto, text);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ProxyRequestDto.text 주입 실패", e);
        }
    }

    private static TranslationResponse translationResponse(String text) {
        return TranslationResponse.builder()
                .translations(List.of(TranslationResponse.Translation.builder()
                        .text(text)
                        .build()))
                .latencyMs(120)
                .build();
    }

    /** {@code UsageLog}는 ProxyRequest 없이는 조립되지 않는다. 여기서는 반환값만 필요하다. */
    private static UsageLog usageLog(int savedTokens, String savedCostUsd) {
        UsageLog log = mock(UsageLog.class);
        given(log.getSavedTokens()).willReturn(savedTokens);
        given(log.getSavedCostUsd()).willReturn(new BigDecimal(savedCostUsd));
        return log;
    }

    private List<TranslationRequest> capturedTranslations() {
        ArgumentCaptor<TranslationRequest> captor = ArgumentCaptor.forClass(TranslationRequest.class);
        verify(translationClient, times(2)).translate(captor.capture());
        return captor.getAllValues();
    }

    private LlmRequest capturedLlmRequest() {
        ArgumentCaptor<LlmRequest> captor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmClient).complete(captor.capture());
        return captor.getValue();
    }

    private UsageRecordRequest capturedUsageRecord() {
        ArgumentCaptor<UsageRecordRequest> captor = ArgumentCaptor.forClass(UsageRecordRequest.class);
        verify(usageRecorder).record(captor.capture());
        return captor.getValue();
    }
}
