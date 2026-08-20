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
import com.borderless.proxy.client.FastApiMorphologyClient;
import com.borderless.proxy.client.OpenAiLlmClient;
import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.client.config.MorphologyProperties;
import com.borderless.proxy.client.config.OpenAiProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.glossary.entity.GlossaryTerm;
import com.borderless.proxy.glossary.repository.GlossaryTermRepository;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import com.borderless.proxy.member.entity.Member;
import com.borderless.proxy.member.repository.MemberRepository;
import com.borderless.proxy.prompt.SystemPromptBuilder;
import com.borderless.proxy.proxy.dto.ProxyResponseDTO;
import com.borderless.proxy.proxy.repository.ProxyRequestRepository;
import com.borderless.proxy.routing.CostRouter;
import com.borderless.proxy.routing.LanguageDetector;
import com.borderless.proxy.routing.TokenCalculator;
import com.borderless.proxy.routing.config.RoutingProperties;
import com.borderless.proxy.routing.dto.RoutingResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 파이프라인 실측. 실제 OpenAI / DeepL / 형태소 사이드카를 호출한다.
 *
 * <p><b>과금이 발생한다.</b> 시나리오마다 LLM을 2회(기준선 1회 + 프록시 1회) 호출하고,
 * 피벗 티어는 DeepL을 2회 더 호출한다. 평소에는 스킵되며 실행하려면 환경변수가 필요하다.
 *
 * <pre>
 *   RUN_PIPELINE_MEASUREMENT=true
 *   OPENAI_API_KEY / OPENAI_BASE_URL / OPENAI_DEFAULT_MODEL
 *   DEEPL_API_KEY / DEEPL_BASE_URL
 *   MORPHOLOGY_BASE_URL=http://127.0.0.1:8000
 * </pre>
 *
 * <p><b>무엇을 재는가.</b> 같은 질문을 두 경로로 보내 실제 청구 근거값을 비교한다.
 * <ul>
 *   <li>기준선: 원문을 그대로 LLM에 보낸다. 프록시를 쓰지 않았을 때의 비용이다</li>
 *   <li>프록시: {@link ProxyOrchestrator}를 그대로 태운다. 마스킹·형태소·피벗·재번역·복원을 모두 거친다</li>
 * </ul>
 *
 * <p>토큰 수는 로컬 추정치가 아니라 <b>API가 보고한 실측값</b>을 쓴다. 시스템 프롬프트
 * 오버헤드와 채팅 포맷 오버헤드가 포함된 값이라야 실제 청구액과 맞는다.
 *
 * <p><b>DB 없이 돈다.</b> 리포지토리는 목이고 계산기({@link TokenSavingsCalculator},
 * {@link CostCalculator})는 실제 객체다. 저장 경로는 검증하지 않으며 금액 산술만 실측한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_PIPELINE_MEASUREMENT", matches = "true")
@DisplayName("파이프라인 실측")
class PipelineMeasurementTest {

    private static final Long MEMBER_ID = 1L;

    /** application.yaml의 billing 설정과 같은 값. 실제 청구 기준으로 환산하기 위함이다. */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.10");
    private static final Map<String, ModelPricing> PRICING = Map.of(
            "gpt-4.1-mini", new ModelPricing(new BigDecimal("0.40"), new BigDecimal("1.60")),
            "gpt-4o", new ModelPricing(new BigDecimal("2.50"), new BigDecimal("10.00")),
            "gpt-4o-mini", new ModelPricing(new BigDecimal("0.15"), new BigDecimal("0.60")));

    /** 용어집. 실제 마스킹 토큰 형식(UUID 앞 12자리 대문자)을 쓴다. */
    private static final List<Term> TERMS = List.of(
            new Term("Sigasig Platform", "{TERM_3F9A2B7C1D0E}"),
            new Term("Nền tảng Sigasig", "{TERM_88AA11BB22CC}"),
            new Term("Plataporma Sigasig", "{TERM_5C4D3E2F1A0B}"));

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("SKIP 기대 · 짧은 영어 질문", null,
                    "What is a reverse proxy?"),

            new Scenario("TIER_1 기대 · 영어 장문", "Sigasig Platform", """
                    Our team is preparing the quarterly rollout of the Sigasig Platform for three \
                    regional offices, and we need a clear migration plan. Please explain how we \
                    should sequence the database schema changes, the background worker deployment, \
                    and the client cutover so that we can keep downtime under fifteen minutes \
                    during the maintenance window this weekend."""),

            new Scenario("TIER_2 기대 · 베트남어 장문", "Nền tảng Sigasig", """
                    Nhóm của chúng tôi đang chuẩn bị triển khai Nền tảng Sigasig cho ba chi nhánh \
                    trong quý này và cần một kế hoạch di chuyển rõ ràng. Vui lòng giải thích chúng \
                    tôi nên sắp xếp thứ tự thay đổi lược đồ cơ sở dữ liệu, triển khai tiến trình \
                    nền và chuyển đổi phía máy khách như thế nào để giữ thời gian ngừng hoạt động \
                    dưới mười lăm phút trong khung bảo trì cuối tuần này."""),

            new Scenario("TIER_3 기대 · 타갈로그 장문", "Plataporma Sigasig", """
                    Naghahanda ang aming pangkat para sa paglulunsad ng Plataporma Sigasig sa \
                    tatlong sangay ngayong quarter at nagsusulat kami ng malinaw na plano sa \
                    paglilipat. Ipaliwanag mo kung paano namin dapat isunod-sunod ang pagbabago sa \
                    schema ng database, ang paglalagay ng background worker, at ang paglilipat ng \
                    kliyente upang mapanatili naming mababa sa labinlimang minuto ang downtime sa \
                    maintenance window ngayong katapusan ng linggo."""));

    private static final List<Result> RESULTS = new ArrayList<>();

    private final TokenCalculator tokenCalculator = new TokenCalculator();

    @Test
    @DisplayName("티어별로 기준선과 프록시 경로의 실측 사용량을 비교한다")
    void measure() {
        OpenAiProperties openAi = openAiProperties();
        OpenAiLlmClient llmClient = new OpenAiLlmClient(openAiWebClient(openAi), openAi);

        CostRouter router = new CostRouter(
                tokenCalculator, new LanguageDetector(), new RoutingProperties(50, 0.7));

        for (Scenario scenario : SCENARIOS) {
            RESULTS.add(runScenario(scenario, router, llmClient, openAi));
        }
    }

    private Result runScenario(Scenario scenario, CostRouter router,
                               OpenAiLlmClient llmClient, OpenAiProperties openAi) {

        RoutingResult routing = router.route(scenario.text());

        // --- 기준선: 원문을 그대로 보낸다. 시스템 프롬프트도 없다 ---
        long baselineStart = System.nanoTime();
        LlmResponse baseline = llmClient.complete(LlmRequest.of(scenario.text())).block();
        long baselineWallMs = (System.nanoTime() - baselineStart) / 1_000_000;

        // --- 프록시: 오케스트레이터를 그대로 태운다 ---
        UsageRecorder recorder = spy(usageRecorder());
        ProxyOrchestrator orchestrator = new ProxyOrchestrator(
                router,
                new TermMasker(glossaryRepository()),
                new TermRestorer(),
                new DeepLTranslationClient(deepLWebClient(), deepLProperties()),
                new FastApiMorphologyClient(morphologyWebClient(), morphologyProperties(true)),
                morphologyProperties(true),
                llmClient,
                new SystemPromptBuilder(),
                recorder,
                usageSummaryService());

        long proxyStart = System.nanoTime();
        ProxyResponseDTO response = orchestrator.process(request(scenario.text()), MEMBER_ID);
        long proxyWallMs = (System.nanoTime() - proxyStart) / 1_000_000;

        ArgumentCaptor<UsageRecordRequest> captor = ArgumentCaptor.forClass(UsageRecordRequest.class);
        verify(recorder).record(captor.capture());
        UsageRecordRequest recorded = captor.getValue();

        return new Result(
                scenario,
                routing,
                baseline,
                baselineWallMs,
                recorded,
                response,
                proxyWallMs,
                cost(baseline.getModel(),
                        baseline.getUsage().getPromptTokens(),
                        baseline.getUsage().getCompletionTokens()),
                cost(recorded.modelName(),
                        recorded.usage().inputTokens(),
                        recorded.usage().outputTokens()),
                openAi.getModel());
    }

    /**
     * 형태소 힌트가 값을 하는지 A/B로 가른다.
     *
     * <p>같은 타갈로그 원문을 두 번 태운다. 한 번은 사이드카를 붙이고, 한 번은 빈 힌트를 주는
     * 스텁으로 바꾼다. 힌트가 붙는 비용(입력 토큰)과 그 대가로 얻는 것을 나란히 놓고 본다.
     *
     * <p>이 비교가 필요한 이유: 힌트는 시스템 프롬프트에 실려 <b>요청마다 입력 토큰으로 과금된다.</b>
     * 토큰 절감이 목적인 서비스에서 효과가 확인되지 않은 문장을 매 요청에 얹으면 그 자체가 손해다.
     */
    @Test
    @DisplayName("형태소 힌트 유무를 A/B로 비교한다")
    void compareMorphologyHint() throws IOException {
        OpenAiProperties openAi = openAiProperties();
        OpenAiLlmClient llmClient = new OpenAiLlmClient(openAiWebClient(openAi), openAi);
        CostRouter router = new CostRouter(
                tokenCalculator, new LanguageDetector(), new RoutingProperties(50, 0.7));

        Scenario tagalog = SCENARIOS.get(3);

        UsageRecordRequest withHint = runOnce(tagalog, router, llmClient,
                new FastApiMorphologyClient(morphologyWebClient(), morphologyProperties(true)), true);

        // 사이드카가 죽었을 때와 같은 상태. 클라이언트가 MorphologyHints.empty()로 대체하는 경로다.
        UsageRecordRequest withoutHint = runOnce(tagalog, router, llmClient,
                text -> reactor.core.publisher.Mono.just(
                        com.borderless.proxy.client.dto.MorphologyHints.empty(text)), true);

        String hintLine = hintLine(withHint.sentText());

        StringBuilder out = new StringBuilder();
        out.append("# 형태소 힌트 A/B\n\n");
        out.append("| 항목 | 힌트 있음 | 힌트 없음 | 차이 |\n|---|---|---|---|\n");
        out.append(row("보고 입력 토큰",
                withHint.usage().inputTokens(), withoutHint.usage().inputTokens()));
        out.append(row("보고 출력 토큰",
                withHint.usage().outputTokens(), withoutHint.usage().outputTokens()));
        out.append(row("전송문 길이(문자)",
                length(withHint.sentText()), length(withoutHint.sentText())));
        out.append(row("전송문 토큰(로컬 계산)",
                tokenCalculator.countTokens(withHint.sentText()),
                tokenCalculator.countTokens(withoutHint.sentText())));

        out.append("\n힌트 문장 (").append(tokenCalculator.countTokens(hintLine))
                .append(" 토큰, ").append(hintLine.length()).append("자)\n\n```\n")
                .append(hintLine).append("\n```\n");

        out.append("\n## 힌트 있음 · LLM 원본 답변\n\n```\n")
                .append(withHint.actualAnswerText()).append("\n```\n");
        out.append("\n## 힌트 없음 · LLM 원본 답변\n\n```\n")
                .append(withoutHint.actualAnswerText()).append("\n```\n");

        Path path = Path.of("build", "measurement", "morphology-ab.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, out.toString());
        System.out.println("형태소 A/B 데이터: " + path.toAbsolutePath());
    }

    private UsageRecordRequest runOnce(Scenario scenario, CostRouter router,
                                       OpenAiLlmClient llmClient,
                                       com.borderless.proxy.client.MorphologyClient morphologyClient,
                                       boolean injectHint) {
        UsageRecorder recorder = spy(usageRecorder());
        ProxyOrchestrator orchestrator = new ProxyOrchestrator(
                router,
                new TermMasker(glossaryRepository()),
                new TermRestorer(),
                new DeepLTranslationClient(deepLWebClient(), deepLProperties()),
                morphologyClient,
                morphologyProperties(injectHint),
                llmClient,
                new SystemPromptBuilder(),
                recorder,
                usageSummaryService());

        orchestrator.process(request(scenario.text()), MEMBER_ID);

        ArgumentCaptor<UsageRecordRequest> captor = ArgumentCaptor.forClass(UsageRecordRequest.class);
        verify(recorder).record(captor.capture());
        return captor.getValue();
    }

    /** 전송문에서 형태소 힌트 줄만 뽑아낸다. 없으면 빈 문자열. */
    private static String hintLine(String sentText) {
        if (sentText == null) {
            return "";
        }
        return sentText.lines()
                .filter(line -> line.startsWith("Tagalog morphology hints"))
                .findFirst()
                .orElse("");
    }

    private static String row(String label, int withHint, int withoutHint) {
        return "| %s | %d | %d | %s |%n".formatted(
                label, withHint, withoutHint,
                (withHint - withoutHint > 0 ? "+" : "") + (withHint - withoutHint));
    }

    // ---------------------------------------------------------------------
    // 리포트
    // ---------------------------------------------------------------------

    @AfterAll
    static void writeReport() throws IOException {
        if (RESULTS.isEmpty()) {
            return;
        }

        StringBuilder out = new StringBuilder();
        out.append("# 파이프라인 실측 원본 데이터\n\n")
                .append("측정 시각: ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .append("\n\n");

        out.append("## 요약\n\n")
                .append("| 시나리오 | 티어 | 감지 | 신뢰도 | 추정토큰 "
                        + "| 기준입력 | 기준출력 | 기준비용 | 프록시입력 | 프록시출력 | 프록시비용 "
                        + "| 비용증감 | 절감토큰 | 절감액 | 기준지연 | 프록시지연 |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");

        for (Result r : RESULTS) {
            BigDecimal diff = r.proxyCost().subtract(r.baselineCost());
            out.append("| ").append(r.scenario().name())
                    .append(" | ").append(r.routing().tier())
                    .append(" | ").append(r.routing().detectedLanguage())
                    .append(" | ").append(round(r.routing().confidence()))
                    .append(" | ").append(r.routing().estimatedTokens())
                    .append(" | ").append(r.baseline().getUsage().getPromptTokens())
                    .append(" | ").append(r.baseline().getUsage().getCompletionTokens())
                    .append(" | ").append(r.baselineCost().toPlainString())
                    .append(" | ").append(r.recorded().usage().inputTokens())
                    .append(" | ").append(r.recorded().usage().outputTokens())
                    .append(" | ").append(r.proxyCost().toPlainString())
                    .append(" | ").append(signed(diff))
                    .append(" | ").append(r.response().getSavedTokens())
                    .append(" | ").append(r.response().getSavedCostUsd().toPlainString())
                    .append(" | ").append(r.baselineWallMs()).append("ms")
                    .append(" | ").append(r.proxyWallMs()).append("ms")
                    .append(" |\n");
        }

        out.append("\n## 시나리오 상세\n");
        for (Result r : RESULTS) {
            out.append("\n### ").append(r.scenario().name()).append("\n\n");
            appendDetail(out, r);
        }

        Path path = Path.of("build", "measurement", "result.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, out.toString());
        System.out.println("실측 원본 데이터: " + path.toAbsolutePath());
    }

    private static void appendDetail(StringBuilder out, Result r) {
        Map<String, String> rows = new LinkedHashMap<>();
        rows.put("요청 모델(설정)", r.configuredModel());
        rows.put("응답 모델(보고)", r.recorded().modelName());
        rows.put("라우팅", "%s / lang=%s / conf=%s / tokens=%d".formatted(
                r.routing().tier(), r.routing().detectedLanguage(),
                round(r.routing().confidence()), r.routing().estimatedTokens()));
        rows.put("용어집 대상", r.scenario().term() == null ? "없음" : r.scenario().term());
        rows.put("마스킹 적용", String.valueOf(r.recorded().sentText() != null
                && TERMS.stream().anyMatch(t -> r.recorded().sentText().contains(t.token()))));
        rows.put("시스템 프롬프트 존재", String.valueOf(r.hasSystemPrompt()));
        rows.put("전송문 길이(문자)", String.valueOf(length(r.recorded().sentText())));
        rows.put("원문 길이(문자)", String.valueOf(length(r.recorded().originalText())));
        rows.put("복원 성공(용어 노출)", String.valueOf(r.restoredCleanly()));
        rows.put("최종 답변에 토큰 잔존", String.valueOf(r.leakedToken()));

        out.append("| 항목 | 값 |\n|---|---|\n");
        rows.forEach((k, v) -> out.append("| ").append(k).append(" | ").append(v).append(" |\n"));

        out.append("\n원문\n\n```\n").append(r.scenario().text()).append("\n```\n");
        out.append("\nLLM에 실제로 보낸 텍스트(시스템 프롬프트 포함)\n\n```\n")
                .append(r.recorded().sentText()).append("\n```\n");
        out.append("\n기준선 답변\n\n```\n").append(r.baseline().getContent()).append("\n```\n");
        out.append("\nLLM 원본 답변(프록시)\n\n```\n")
                .append(r.recorded().actualAnswerText()).append("\n```\n");
        out.append("\n최종 사용자 응답\n\n```\n").append(r.response().getResult()).append("\n```\n");
    }

    // ---------------------------------------------------------------------
    // 조립
    // ---------------------------------------------------------------------

    private UsageRecorder usageRecorder() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        ProxyRequestRepository proxyRequestRepository = mock(ProxyRequestRepository.class);
        UsageLogRepository usageLogRepository = mock(UsageLogRepository.class);

        given(memberRepository.getReferenceById(any())).willReturn(mock(Member.class));
        given(proxyRequestRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(usageLogRepository.save(any())).willAnswer(call -> call.getArgument(0));

        return new UsageRecorder(
                new TokenSavingsCalculator(tokenCalculator),
                new CostCalculator(new BillingProperties(FEE_RATE, PRICING)),
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
        // TermMasker가 반환 리스트를 정렬하므로 가변 리스트를 줘야 한다.
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
        String baseUrl = env("DEEPL_BASE_URL");
        properties.setBaseUrl(baseUrl == null || baseUrl.isBlank()
                ? properties.recommendedBaseUrl() : baseUrl);
        return properties;
    }

    private static WebClient deepLWebClient() {
        DeepLProperties properties = deepLProperties();
        return builder(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .build();
    }

    private static MorphologyProperties morphologyProperties(boolean injectHint) {
        MorphologyProperties properties = new MorphologyProperties();
        properties.setBaseUrl(env("MORPHOLOGY_BASE_URL"));
        properties.setTimeout(Duration.ofSeconds(5));
        // 실측에서는 실패를 삼키면 안 된다. 힌트가 왜 비었는지 알 수 없게 된다.
        properties.setOptional(false);
        // 프로덕션 기본값은 꺼짐이다. 실측은 켠 상태와 끈 상태를 모두 재려고 인자로 받는다.
        properties.setInjectHint(injectHint);
        return properties;
    }

    private static WebClient morphologyWebClient() {
        return builder(morphologyProperties(true).getBaseUrl()).build();
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

    private static BigDecimal cost(String modelName, int inputTokens, int outputTokens) {
        ModelPricing pricing = new BillingProperties(FEE_RATE, PRICING).pricingFor(modelName);
        return rate(inputTokens, pricing.inputPerMillion()).add(rate(outputTokens, pricing.outputPerMillion()));
    }

    private static BigDecimal rate(int tokens, BigDecimal perMillion) {
        return perMillion.multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private static String signed(BigDecimal value) {
        return value.signum() > 0 ? "+" + value.toPlainString() : value.toPlainString();
    }

    private static String round(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }

    // ---------------------------------------------------------------------
    // 값 객체
    // ---------------------------------------------------------------------

    private record Term(String source, String token) { }

    private record Scenario(String name, String term, String text) { }

    private record Result(
            Scenario scenario,
            RoutingResult routing,
            LlmResponse baseline,
            long baselineWallMs,
            UsageRecordRequest recorded,
            ProxyResponseDTO response,
            long proxyWallMs,
            BigDecimal baselineCost,
            BigDecimal proxyCost,
            String configuredModel
    ) {

        /** 전송문이 원문보다 길면 시스템 프롬프트가 붙었다는 신호다. */
        boolean hasSystemPrompt() {
            return recorded.sentText() != null
                    && !recorded.sentText().equals(recorded.originalText())
                    && recorded.sentText().contains("\n");
        }

        /** 마스킹한 용어가 최종 응답에 원래 표기로 돌아왔는지. */
        boolean restoredCleanly() {
            if (scenario.term() == null) {
                return true;
            }
            return response.getResult() != null && response.getResult().contains(scenario.term());
        }

        /** 치환 토큰이 사용자 화면까지 새어나갔는지. true면 복원 실패다. */
        boolean leakedToken() {
            return response.getResult() != null
                    && TERMS.stream().anyMatch(t -> response.getResult().contains(t.token()));
        }
    }
}
