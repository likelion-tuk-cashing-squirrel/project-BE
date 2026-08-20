package com.borderless.proxy.service;

import com.borderless.proxy.billing.CostCalculator;
import com.borderless.proxy.billing.TokenSavingsCalculator;
import com.borderless.proxy.billing.UsageRecorder;
import com.borderless.proxy.billing.config.BillingProperties;
import com.borderless.proxy.billing.config.ModelPricing;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.repository.UsageLogRepository;
import com.borderless.proxy.billing.service.UsageSummaryService;
import com.borderless.proxy.client.DeepLTranslationClient;
import com.borderless.proxy.client.MorphologyClient;
import com.borderless.proxy.client.OpenAiLlmClient;
import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.client.config.MorphologyProperties;
import com.borderless.proxy.client.config.OpenAiProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.MorphologyHints;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.glossary.entity.GlossaryTerm;
import com.borderless.proxy.glossary.repository.GlossaryTermRepository;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.member.entity.Member;
import com.borderless.proxy.member.repository.MemberRepository;
import com.borderless.proxy.prompt.SystemPromptBuilder;
import com.borderless.proxy.proxy.repository.ProxyRequestRepository;
import com.borderless.proxy.routing.CostRouter;
import com.borderless.proxy.routing.LanguageDetector;
import com.borderless.proxy.routing.TokenCalculator;
import com.borderless.proxy.routing.config.RoutingProperties;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 절감 원인 분리 실험 + 티어별 재측정.
 *
 * <p><b>과금이 발생한다.</b> OpenAI 약 29회, DeepL 약 4회를 호출한다.
 * {@code gpt-4.1-mini} 기준 $0.05 안쪽이다.
 *
 * <pre>
 *   RUN_CONTROLLED_EXPERIMENT=true
 *   OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_DEFAULT_MODEL
 *   DEEPL_API_KEY / DEEPL_BASE_URL
 * </pre>
 *
 * <p><b>왜 이 실험이 필요한가.</b> 기존 실측({@code docs/pipeline-measurement.md})에서 장문 요청
 * 비용이 35~45% 줄었는데, 피벗을 하지 않는 TIER_1이 피벗 티어보다 더 크게 떨어졌다.
 * 티어마다 변수 세 개(시스템 프롬프트·마스킹·피벗)를 동시에 바꿨기 때문에 기여도를 분리할 수 없다.
 * 이 테스트는 변수를 하나씩만 바꿔 어느 것이 출력 토큰을 줄이는지 가른다.
 *
 * <p><b>형태소는 쓰지 않는다.</b> 프로덕션 기본값이 꺼짐이고 제거 예정이라
 * 사이드카 없이 도는 구성으로 측정한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_CONTROLLED_EXPERIMENT", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("절감 원인 분리 실험")
class ControlledExperimentTest {

    private static final Long MEMBER_ID = 1L;

    private static final BigDecimal FEE_RATE = new BigDecimal("0.10");
    private static final ModelPricing MINI = new ModelPricing(new BigDecimal("0.40"), new BigDecimal("1.60"));
    private static final BillingProperties BILLING = new BillingProperties(FEE_RATE, java.util.Map.of(
            "gpt-4.1-mini", MINI,
            "gpt-4o", new ModelPricing(new BigDecimal("2.50"), new BigDecimal("10.00")),
            "gpt-4o-mini", new ModelPricing(new BigDecimal("0.15"), new BigDecimal("0.60"))));

    /** 현재 프로덕션 형식({@code {TERM_<PK>}})을 쓴다. 구 UUID 형식으로 재면 절감분이 과대 계상된다. */
    private static final List<Term> TERMS = List.of(
            new Term("Sigasig Platform", "{TERM_1}"),
            new Term("Nền tảng Sigasig", "{TERM_2}"),
            new Term("Plataporma Sigasig", "{TERM_3}"));

    private static final String EN_TEXT = """
            Our team is preparing the quarterly rollout of the Sigasig Platform for three \
            regional offices, and we need a clear migration plan. Please explain how we \
            should sequence the database schema changes, the background worker deployment, \
            and the client cutover so that we can keep downtime under fifteen minutes \
            during the maintenance window this weekend.""";

    private static final String VI_TEXT = """
            Nhóm của chúng tôi đang chuẩn bị triển khai Nền tảng Sigasig cho ba chi nhánh \
            trong quý này và cần một kế hoạch di chuyển rõ ràng. Vui lòng giải thích chúng \
            tôi nên sắp xếp thứ tự thay đổi lược đồ cơ sở dữ liệu, triển khai tiến trình \
            nền và chuyển đổi phía máy khách như thế nào để giữ thời gian ngừng hoạt động \
            dưới mười lăm phút trong khung bảo trì cuối tuần này.""";

    private static final String TL_TEXT = """
            Naghahanda ang aming pangkat para sa paglulunsad ng Plataporma Sigasig sa \
            tatlong sangay ngayong quarter at nagsusulat kami ng malinaw na plano sa \
            paglilipat. Ipaliwanag mo kung paano namin dapat isunod-sunod ang pagbabago sa \
            schema ng database, ang paglalagay ng background worker, at ang paglilipat ng \
            kliyente upang mapanatili naming mababa sa labinlimang minuto ang downtime sa \
            maintenance window ngayong katapusan ng linggo.""";

    private static final String SHORT_EN = "What is a reverse proxy?";

    /** {@code SystemPromptBuilder}가 쓰는 것과 같은 문구. 셀별로 조합을 직접 만들어야 해서 복제한다. */
    private static final String ENGLISH_ONLY = "Answer in English only.";

    /** 출력 토큰은 같은 입력에도 흔들린다. 원인을 가르려면 반복이 필요하다. */
    private static final int REPS = 3;

    /**
     * 호출 간격. 계정 한도가 10 RPM이라 연속 호출하면 429가 난다.
     *
     * <p>클라이언트에 재시도가 있지만 21회를 몰아서 던지면 재시도까지 소진된다.
     * 측정 목적이라 속도보다 완주가 중요하므로 호출부에서 간격을 준다.
     */
    private static final long PACE_MS = 500;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * DeepL 한계 단가. Growth 플랜 초과분 기준 $27.50 / 100만자.
     *
     * <p>Developer(무료) 키는 총 100만자까지 $0라서 지금 당장은 비용이 안 나가지만,
     * 그건 한도 소진 전까지의 이야기다. 원가를 보려면 유료 단가로 환산해야 한다.
     */
    private static final BigDecimal DEEPL_PER_MILLION = new BigDecimal("27.50");

    private final TokenCalculator tokenCalculator = new TokenCalculator();

    // =====================================================================
    // 1. 티어별 재측정 (현재 코드 기준)
    // =====================================================================

    @Test
    @Order(1)
    @DisplayName("티어별로 기준선과 프록시의 입력·출력 토큰을 다시 잰다")
    void measureTiers() throws IOException {
        OpenAiProperties openAi = openAiProperties();
        OpenAiLlmClient llmClient = new OpenAiLlmClient(openAiWebClient(openAi), openAi);
        CostRouter router = new CostRouter(
                tokenCalculator, new LanguageDetector(), new RoutingProperties(50, 0.7));

        List<TierRow> rows = new ArrayList<>();
        rows.add(runTier("짧은 영어 질문", "영어", SHORT_EN, router, llmClient));
        rows.add(runTier("영어 장문", "영어", EN_TEXT, router, llmClient));
        rows.add(runTier("베트남어 장문", "베트남어", VI_TEXT, router, llmClient));
        rows.add(runTier("타갈로그 장문", "타갈로그", TL_TEXT, router, llmClient));

        StringBuilder out = new StringBuilder();
        out.append("# 티어별 재측정 (현재 코드)\n\n");
        out.append("측정 시각 ").append(LocalDateTime.now().format(STAMP))
                .append(" · 모델 ").append(rows.get(0).model())
                .append(" · 형태소 힌트 꺼짐\n\n");

        out.append("## 입력 토큰\n\n");
        out.append("| 티어 | 언어 | 감지 | 기준선 | 프록시 | 변화 |\n|---|---|---|---:|---:|---:|\n");
        for (TierRow r : rows) {
            out.append("| `%s` | %s | %s | %d | %d | %s |%n".formatted(
                    r.tier(), r.language(), r.detected(),
                    r.baseInput(), r.proxyInput(), pct(r.baseInput(), r.proxyInput())));
        }

        out.append("\n## 출력 토큰\n\n");
        out.append("| 티어 | 언어 | 기준선 | 프록시 | 변화 |\n|---|---|---:|---:|---:|\n");
        for (TierRow r : rows) {
            out.append("| `%s` | %s | %d | %d | %s |%n".formatted(
                    r.tier(), r.language(), r.baseOutput(), r.proxyOutput(),
                    pct(r.baseOutput(), r.proxyOutput())));
        }

        out.append("\n## 비용 (USD)\n\n");
        out.append("| 티어 | 언어 | 기준선 | 프록시 | 변화 | `savedTokens` | `savedCostUsd` |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|\n");
        for (TierRow r : rows) {
            out.append("| `%s` | %s | %s | %s | %s | %d | %s |%n".formatted(
                    r.tier(), r.language(),
                    r.baseCost().toPlainString(), r.proxyCost().toPlainString(),
                    pct(r.baseCost(), r.proxyCost()),
                    r.savedTokens(), r.savedCostUsd().toPlainString()));
        }

        write("tier-remeasure.md", out.toString());
        System.out.println(out);
    }

    private TierRow runTier(String label, String language, String text,
                            CostRouter router, OpenAiLlmClient llmClient) {

        RoutingResult routing = router.route(text);

        LlmResponse baseline = llmClient.complete(LlmRequest.of(text)).block();

        UsageRecorder recorder = spy(usageRecorder());
        MorphologyClient noHint = t -> Mono.just(MorphologyHints.empty(t));

        ProxyOrchestrator orchestrator = new ProxyOrchestrator(
                router,
                new TermMasker(glossaryRepository()),
                new TermRestorer(),
                new DeepLTranslationClient(deepLWebClient(), deepLProperties()),
                noHint,
                morphologyProperties(),
                llmClient,
                new SystemPromptBuilder(),
                recorder,
                usageSummaryService());

        orchestrator.process(request(text), MEMBER_ID);

        ArgumentCaptor<UsageRecordRequest> captor = ArgumentCaptor.forClass(UsageRecordRequest.class);
        verify(recorder).record(captor.capture());
        UsageRecordRequest recorded = captor.getValue();

        var savings = new TokenSavingsCalculator(tokenCalculator);
        var input = savings.compareInput(recorded.originalText(), recorded.sentText());
        var output = savings.compareOutput(recorded.nativeAnswerText(), recorded.actualAnswerText());
        int savedTokens = input.saved() + output.saved();

        BigDecimal savedCost = rate(input.saved(), MINI.inputPerMillion())
                .add(rate(output.saved(), MINI.outputPerMillion()));

        return new TierRow(
                routing.tier().name(),
                language,
                routing.detectedLanguage(),
                baseline.getUsage().getPromptTokens(),
                baseline.getUsage().getCompletionTokens(),
                recorded.usage().inputTokens(),
                recorded.usage().outputTokens(),
                cost(baseline.getUsage().getPromptTokens(), baseline.getUsage().getCompletionTokens()),
                cost(recorded.usage().inputTokens(), recorded.usage().outputTokens()),
                savedTokens,
                savedCost,
                recorded.modelName());
    }

    // =====================================================================
    // 1-B. 전체 원가 (OpenAI + DeepL)
    // =====================================================================

    /**
     * DeepL 비용까지 포함한 전체 절감액을 만든다.
     *
     * <p>{@code UsageRecorder}는 DeepL 요금을 절감액에서 차감하지 않는다(의도된 결정).
     * 그래서 회사 손익 관점 수치가 따로 필요하다. DeepL은 원문 문자 수로 과금하고
     * 응답의 {@code billed_characters}가 그 값이라, 클라이언트를 감싸 누적한다.
     */
    @Test
    @Order(3)
    @DisplayName("OpenAI와 DeepL 비용을 합쳐 전체 절감액을 낸다")
    void measureFullCost() throws IOException {
        OpenAiProperties openAi = openAiProperties();
        OpenAiLlmClient llmClient = new OpenAiLlmClient(openAiWebClient(openAi), openAi);
        CostRouter router = new CostRouter(
                tokenCalculator, new LanguageDetector(), new RoutingProperties(50, 0.7));

        List<FullCostRow> rows = new ArrayList<>();
        rows.add(runFullCost("짧은 영어 질문", "영어", SHORT_EN, router, llmClient));
        rows.add(runFullCost("영어 장문", "영어", EN_TEXT, router, llmClient));
        rows.add(runFullCost("베트남어 장문", "베트남어", VI_TEXT, router, llmClient));
        rows.add(runFullCost("타갈로그 장문", "타갈로그", TL_TEXT, router, llmClient));

        StringBuilder out = new StringBuilder();
        out.append("# 전체 절감액 (OpenAI + DeepL)\n\n");
        out.append("측정 시각 ").append(LocalDateTime.now().format(STAMP))
                .append(" · 모델 ").append(rows.get(0).model())
                .append(" · DeepL $").append(DEEPL_PER_MILLION.toPlainString()).append("/1M자\n\n");

        out.append("## DeepL 사용량\n\n");
        out.append("| 티어 | 언어 | 호출 | 과금 문자 | DeepL 비용 |\n|---|---|---:|---:|---:|\n");
        for (FullCostRow r : rows) {
            out.append("| `%s` | %s | %d | %d | %s |%n".formatted(
                    r.tier(), r.language(), r.deepLCalls(), r.deepLChars(), scale(r.deepLCost())));
        }

        out.append("\n## 전체 절감액\n\n");
        out.append("| 티어 | 언어 | 기준선 OpenAI | 프록시 OpenAI | 프록시 DeepL | 프록시 합계 | 절감액 | 절감률 |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (FullCostRow r : rows) {
            BigDecimal total = r.proxyOpenAi().add(r.deepLCost());
            BigDecimal saved = r.baseOpenAi().subtract(total);
            out.append("| `%s` | %s | %s | %s | %s | %s | %s | %s |%n".formatted(
                    r.tier(), r.language(),
                    scale(r.baseOpenAi()), scale(r.proxyOpenAi()), scale(r.deepLCost()),
                    scale(total), scale(saved), pct(r.baseOpenAi(), total)));
        }

        out.append("\n## DeepL이 절감액을 얼마나 먹는가\n\n");
        out.append("| 티어 | 언어 | OpenAI 절감 | DeepL 비용 | 잠식률 |\n|---|---|---:|---:|---:|\n");
        for (FullCostRow r : rows) {
            BigDecimal openAiSaved = r.baseOpenAi().subtract(r.proxyOpenAi());
            String eaten = openAiSaved.signum() <= 0 ? "-"
                    : r.deepLCost().multiply(BigDecimal.valueOf(100))
                            .divide(openAiSaved, 1, RoundingMode.HALF_UP).toPlainString() + "%";
            out.append("| `%s` | %s | %s | %s | %s |%n".formatted(
                    r.tier(), r.language(), scale(openAiSaved), scale(r.deepLCost()), eaten));
        }

        write("full-cost.md", out.toString());
        System.out.println(out);
    }

    private FullCostRow runFullCost(String label, String language, String text,
                                    CostRouter router, OpenAiLlmClient llmClient) {

        LlmResponse baseline = llmClient.complete(LlmRequest.of(text)).block();

        CountingTranslationClient deepL = new CountingTranslationClient(
                new DeepLTranslationClient(deepLWebClient(), deepLProperties()));

        UsageRecorder recorder = spy(usageRecorder());
        ProxyOrchestrator orchestrator = new ProxyOrchestrator(
                router,
                new TermMasker(glossaryRepository()),
                new TermRestorer(),
                deepL,
                (MorphologyClient) t -> Mono.just(MorphologyHints.empty(t)),
                morphologyProperties(),
                llmClient,
                new SystemPromptBuilder(),
                recorder,
                usageSummaryService());

        orchestrator.process(request(text), MEMBER_ID);

        ArgumentCaptor<UsageRecordRequest> captor = ArgumentCaptor.forClass(UsageRecordRequest.class);
        verify(recorder).record(captor.capture());
        UsageRecordRequest recorded = captor.getValue();

        BigDecimal deepLCost = DEEPL_PER_MILLION
                .multiply(BigDecimal.valueOf(deepL.characters()))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);

        return new FullCostRow(
                router.route(text).tier().name(),
                language,
                deepL.calls(),
                deepL.characters(),
                deepLCost,
                cost(baseline.getUsage().getPromptTokens(), baseline.getUsage().getCompletionTokens()),
                cost(recorded.usage().inputTokens(), recorded.usage().outputTokens()),
                recorded.modelName());
    }

    /** DeepL 과금 문자 수를 누적한다. 응답의 {@code billed_characters}가 공급자가 센 값이다. */
    private static final class CountingTranslationClient
            implements com.borderless.proxy.client.TranslationClient {

        private final com.borderless.proxy.client.TranslationClient delegate;
        private int characters;
        private int calls;

        private CountingTranslationClient(com.borderless.proxy.client.TranslationClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<com.borderless.proxy.client.dto.TranslationResponse> translate(
                TranslationRequest request) {
            return delegate.translate(request).doOnNext(response -> {
                calls++;
                int billed = response.getTotalBilledCharacters();
                // 무료 키에서 billed_characters가 비어 오면 원문 길이로 대체한다.
                characters += billed > 0 ? billed : request.getTexts().stream().mapToInt(String::length).sum();
            });
        }

        int characters() {
            return characters;
        }

        int calls() {
            return calls;
        }
    }

    // =====================================================================
    // 2. 통제 실험 — 변수를 하나씩만 바꾼다
    // =====================================================================

    @Test
    @Order(2)
    @DisplayName("시스템 프롬프트·마스킹·피벗의 기여도를 분리한다")
    void separateCauses() throws IOException {
        OpenAiProperties openAi = openAiProperties();
        OpenAiLlmClient llmClient = new OpenAiLlmClient(openAiWebClient(openAi), openAi);
        DeepLTranslationClient deepL = new DeepLTranslationClient(deepLWebClient(), deepLProperties());

        // 번역은 셀마다 하지 않고 한 번만 한다. 반복마다 번역하면 본문이 미세하게 달라져
        // 프롬프트 효과와 번역 변동이 섞인다.
        String viMasked = VI_TEXT.replace("Nền tảng Sigasig", "{TERM_2}");
        String pivotPlain = deepL.translate(TranslationRequest.of(VI_TEXT, "VI", "EN")).block().getFirstText();
        String pivotMasked = deepL.translate(TranslationRequest.of(viMasked, "VI", "EN")).block().getFirstText();

        String enMasked = EN_TEXT.replace("Sigasig Platform", "{TERM_1}");

        List<Cell> cells = List.of(
                new Cell("A · 기준선", "베트남어", false, false, false, null, VI_TEXT),
                new Cell("B · 영어답변 지시만", "베트남어", true, false, false, ENGLISH_ONLY, VI_TEXT),
                new Cell("C · 마스킹만", "베트남어", false, true, false, null, viMasked),
                new Cell("D · 피벗만", "베트남어", false, false, true, null, pivotPlain),
                new Cell("E · 전부 (현 TIER_2)", "베트남어", true, true, true,
                        ENGLISH_ONLY + "\nKeep verbatim: {TERM_2}", pivotMasked),

                new Cell("F · 영어 기준선", "영어", false, false, false, null, EN_TEXT),
                new Cell("G · 영어+마스킹 (현 TIER_1)", "영어", true, true, false,
                        "Keep verbatim: {TERM_1}", enMasked));

        List<CellResult> results = new ArrayList<>();
        for (Cell cell : cells) {
            results.add(runCell(cell, llmClient));
        }

        CellResult viBase = results.get(0);
        CellResult enBase = results.get(5);

        StringBuilder out = new StringBuilder();
        out.append("# 통제 실험 · 절감 원인 분리\n\n");
        out.append("측정 시각 ").append(LocalDateTime.now().format(STAMP))
                .append(" · 모델 ").append(viBase.model())
                .append(" · 셀당 ").append(REPS).append("회 평균\n\n");
        out.append("같은 질문에 변수를 하나씩만 바꿔 넣었다. 번역은 한 번만 하고 모든 반복에 재사용했다.\n\n");

        out.append("## 셀 구성\n\n");
        out.append("| 셀 | 언어 | 시스템 프롬프트 | 마스킹 | 피벗 |\n|---|---|:-:|:-:|:-:|\n");
        for (Cell c : cells) {
            out.append("| %s | %s | %s | %s | %s |%n".formatted(
                    c.name(), c.language(), mark(c.prompt()), mark(c.masked()), mark(c.pivot())));
        }

        out.append("\n## 결과 (").append(REPS).append("회 평균)\n\n");
        out.append("| 셀 | 언어 | 입력 | 출력 | 비용 USD | 출력 변화 | 비용 변화 | 답변 언어 |\n");
        out.append("|---|---|---:|---:|---:|---:|---:|---|\n");
        for (CellResult r : results) {
            CellResult base = r.cell().language().equals("영어") ? enBase : viBase;
            out.append("| %s | %s | %.0f | %.0f | %s | %s | %s | %s |%n".formatted(
                    r.cell().name(), r.cell().language(),
                    r.avgInput(), r.avgOutput(), scale(r.avgCost()),
                    r == base ? "기준" : pct(base.avgOutput(), r.avgOutput()),
                    r == base ? "기준" : pct(base.avgCost(), r.avgCost()),
                    r.answerLanguage()));
        }

        out.append("\n## 반복별 출력 토큰 (흔들림 확인)\n\n");
        out.append("| 셀 |");
        for (int i = 1; i <= REPS; i++) {
            out.append(" #").append(i).append(" |");
        }
        out.append(" 최대-최소 |\n|---|");
        out.append("---:|".repeat(REPS)).append("---:|\n");
        for (CellResult r : results) {
            out.append("| ").append(r.cell().name()).append(" |");
            for (int tokens : r.outputs()) {
                out.append(' ').append(tokens).append(" |");
            }
            int spread = r.outputs().stream().max(Integer::compare).orElse(0)
                    - r.outputs().stream().min(Integer::compare).orElse(0);
            out.append(' ').append(spread).append(" |\n");
        }

        out.append("\n## 전송 본문\n\n");
        for (CellResult r : results) {
            out.append("### ").append(r.cell().name()).append("\n\n");
            if (r.cell().systemPrompt() != null) {
                out.append("system\n\n```\n").append(r.cell().systemPrompt()).append("\n```\n\n");
            }
            out.append("user\n\n```\n").append(r.cell().userText()).append("\n```\n\n");
            out.append("답변 첫 200자\n\n```\n").append(preview(r.sampleAnswer())).append("\n```\n\n");
        }

        write("controlled-experiment.md", out.toString());
        System.out.println(out);
    }

    private CellResult runCell(Cell cell, OpenAiLlmClient llmClient) {
        List<Integer> inputs = new ArrayList<>();
        List<Integer> outputs = new ArrayList<>();
        List<BigDecimal> costs = new ArrayList<>();
        String sample = null;
        String model = null;

        for (int i = 0; i < REPS; i++) {
            LlmRequest request = cell.systemPrompt() == null
                    ? LlmRequest.of(cell.userText())
                    : LlmRequest.of(cell.systemPrompt(), cell.userText());

            pace();
            LlmResponse response = llmClient.complete(request).block();

            inputs.add(response.getUsage().getPromptTokens());
            outputs.add(response.getUsage().getCompletionTokens());
            costs.add(cost(response.getUsage().getPromptTokens(), response.getUsage().getCompletionTokens()));

            if (sample == null) {
                sample = response.getContent();
                model = response.getModel();
            }
        }

        return new CellResult(cell, inputs, outputs, costs, sample, model,
                looksEnglish(sample) ? "영어" : "원어");
    }

    // =====================================================================
    // 헬퍼
    // =====================================================================

    private static void pace() {
        try {
            Thread.sleep(PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("측정이 중단되었습니다.", e);
        }
    }

    /** 비ASCII 문자 비율로 답변 언어를 거칠게 가른다. 베트남어는 성조 기호가 많아 확실히 갈린다. */
    private static boolean looksEnglish(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        long nonAscii = text.chars().filter(c -> c > 127).count();
        return (double) nonAscii / text.length() < 0.02;
    }

    private static String mark(boolean on) {
        return on ? "O" : "X";
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + " …";
    }

    private static String pct(double from, double to) {
        if (from == 0) {
            return "-";
        }
        double change = (to - from) / from * 100;
        return "%s%.1f%%".formatted(change > 0 ? "+" : "", change);
    }

    private static String pct(BigDecimal from, BigDecimal to) {
        return pct(from.doubleValue(), to.doubleValue());
    }

    private static String scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static void write(String name, String content) throws IOException {
        Path path = Path.of("build", "measurement", name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        System.out.println("산출물: " + path.toAbsolutePath());
    }

    private static BigDecimal cost(int inputTokens, int outputTokens) {
        return rate(inputTokens, MINI.inputPerMillion()).add(rate(outputTokens, MINI.outputPerMillion()));
    }

    private static BigDecimal rate(int tokens, BigDecimal perMillion) {
        return perMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 8, RoundingMode.HALF_UP);
    }

    private UsageRecorder usageRecorder() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        ProxyRequestRepository proxyRequestRepository = mock(ProxyRequestRepository.class);
        UsageLogRepository usageLogRepository = mock(UsageLogRepository.class);

        given(memberRepository.getReferenceById(any())).willReturn(mock(Member.class));
        given(proxyRequestRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(usageLogRepository.save(any())).willAnswer(call -> call.getArgument(0));

        return new UsageRecorder(
                new TokenSavingsCalculator(tokenCalculator),
                new CostCalculator(BILLING),
                memberRepository,
                proxyRequestRepository,
                usageLogRepository);
    }

    private static UsageSummaryService usageSummaryService() {
        UsageLogRepository repository = mock(UsageLogRepository.class);
        given(repository.sumSavedTokensByMemberId(any())).willReturn(0L);
        given(repository.sumSavedCostUsdByMemberId(any())).willReturn(BigDecimal.ZERO);
        return new UsageSummaryService(repository);
    }

    private static GlossaryTermRepository glossaryRepository() {
        GlossaryTermRepository repository = mock(GlossaryTermRepository.class);
        given(repository.findAllByMemberId(any())).willAnswer(call -> new ArrayList<>(TERMS.stream()
                .map(t -> GlossaryTerm.builder()
                        .sourceTerm(t.source())
                        .maskedToken(t.token())
                        .translation(t.source())
                        .build())
                .toList()));
        return repository;
    }

    private static OpenAiProperties openAiProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setBaseUrl(env("OPENAI_BASE_URL"));
        properties.setApiKey(env("OPENAI_API_KEY"));
        properties.setModel(env("OPENAI_DEFAULT_MODEL"));
        return properties;
    }

    private static WebClient openAiWebClient(OpenAiProperties properties) {
        return builder(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    private static DeepLProperties deepLProperties() {
        DeepLProperties properties = new DeepLProperties();
        properties.setApiKey(env("DEEPL_API_KEY"));
        String baseUrl = System.getenv("DEEPL_BASE_URL");
        properties.setBaseUrl(baseUrl == null || baseUrl.isBlank()
                ? properties.recommendedBaseUrl() : baseUrl);
        properties.setTimeout(Duration.ofSeconds(20));
        return properties;
    }

    private static WebClient deepLWebClient() {
        DeepLProperties properties = deepLProperties();
        return builder(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .build();
    }

    /** 형태소는 쓰지 않는다. 프로덕션 기본값과 같게 꺼둔다. */
    private static MorphologyProperties morphologyProperties() {
        MorphologyProperties properties = new MorphologyProperties();
        properties.setBaseUrl("");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setOptional(true);
        properties.setInjectHint(false);
        return properties;
    }

    private static WebClient.Builder builder(String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(4 * 1024 * 1024));
    }

    private static ProxyRequestDto request(String text) {
        ProxyRequestDto dto = new ProxyRequestDto();
        try {
            java.lang.reflect.Field field = ProxyRequestDto.class.getDeclaredField("text");
            field.setAccessible(true);
            field.set(dto, text);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ProxyRequestDto.text 주입 실패", e);
        }
        return dto;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다.");
        }
        return value;
    }

    // =====================================================================
    // 레코드
    // =====================================================================

    private record Term(String source, String token) { }

    private record Cell(String name, String language,
                        boolean prompt, boolean masked, boolean pivot,
                        String systemPrompt, String userText) { }

    private record CellResult(Cell cell, List<Integer> inputs, List<Integer> outputs,
                              List<BigDecimal> costs, String sampleAnswer, String model,
                              String answerLanguage) {

        double avgInput() {
            return inputs.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        double avgOutput() {
            return outputs.stream().mapToInt(Integer::intValue).average().orElse(0);
        }

        BigDecimal avgCost() {
            return costs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(costs.size()), 8, RoundingMode.HALF_UP);
        }
    }

    private record TierRow(String tier, String language, String detected,
                           int baseInput, int baseOutput, int proxyInput, int proxyOutput,
                           BigDecimal baseCost, BigDecimal proxyCost,
                           int savedTokens, BigDecimal savedCostUsd, String model) { }

    private record FullCostRow(String tier, String language,
                               int deepLCalls, int deepLChars, BigDecimal deepLCost,
                               BigDecimal baseOpenAi, BigDecimal proxyOpenAi, String model) { }
}
