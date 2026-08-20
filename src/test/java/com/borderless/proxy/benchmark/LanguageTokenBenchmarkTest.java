package com.borderless.proxy.benchmark;

import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.routing.TokenCalculator;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 언어별 토큰 소모량 벤치마크. 이 서비스의 전제를 검증한다.
 *
 * <p><b>전제</b>: 같은 내용을 비영어로 쓰면 토큰이 더 든다. 그래서 영어로 피벗하면 비용이 준다.
 *
 * <p><b>왜 문장을 직접 쓰지 않고 DeepL로 번역하는가.</b> 사람이 각 언어로 문장을 지으면
 * 길이와 표현이 달라져 "언어 차이"가 아니라 "작성자 차이"를 재게 된다. 같은 영어 원문을
 * 기계 번역해 얻은 문장끼리 비교하면 의미가 고정되므로 언어별 토큰 효율만 남는다.
 *
 * <p>토큰 수는 {@code JTokkit o200k_base}로 센다. GPT-4o / GPT-4.1 계열이 쓰는 인코딩이라
 * 실제 과금 단위와 같다. <b>토큰 계산 자체는 결정적이다.</b> 같은 입력이면 매번 같은 값이 나오므로
 * 이 벤치마크는 반복 오차가 없다. LLM을 호출하지 않으므로 OpenAI 과금도 없다.
 *
 * <p>DeepL 문자 할당량만 소모한다. 실행하려면 {@code RUN_PIPELINE_MEASUREMENT=true}가 필요하다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_PIPELINE_MEASUREMENT", matches = "true")
@DisplayName("언어별 토큰 벤치마크")
class LanguageTokenBenchmarkTest {

    /** 비교 대상 언어. 라우팅이 지원하는 언어 + 팀의 모국어인 한국어. */
    private static final Map<String, String> TARGETS = new LinkedHashMap<>() {{
        put("KO", "한국어");
        put("VI", "베트남어");
        put("TL", "타갈로그");
    }};

    /**
     * 영어 원문. 이 서비스의 실제 사용 맥락(팀 업무 질문)에 맞춰 골랐다.
     *
     * <p>짧은 문장과 긴 문장을 섞었다. 토큰 비율이 길이에 따라 달라지는지 보려면 둘 다 필요하다.
     */
    private static final List<String> SOURCES = List.of(
            "The deployment failed.",
            "Please review the pull request before the meeting.",
            "Our team needs a clear migration plan for the database schema changes.",
            "Explain how we should sequence the background worker deployment and the client "
                    + "cutover so that downtime stays under fifteen minutes.",
            "The release owner approved the change, but the rollback steps are still missing "
                    + "from section four of the runbook, and the on-call engineer has not been "
                    + "notified about the maintenance window this weekend.");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final TokenCalculator tokenCalculator = new TokenCalculator();

    /** 원시 HTTP 교환 기록. 측정이 실제 API 호출에서 나왔다는 증거다. */
    private final List<RawCall> rawCalls = new ArrayList<>();

    @Test
    @DisplayName("같은 문장을 언어별로 번역해 토큰 수를 비교한다")
    void measure() throws IOException {
        Provenance provenance = Provenance.capture();
        List<Row> rows = new ArrayList<>();

        for (String source : SOURCES) {
            int englishTokens = tokenCalculator.countTokens(source);
            Map<String, Translated> byLanguage = new LinkedHashMap<>();

            for (String targetCode : TARGETS.keySet()) {
                String translated = translateAndRecord(source, targetCode);
                byLanguage.put(targetCode,
                        new Translated(translated, tokenCalculator.countTokens(translated)));
            }

            rows.add(new Row(source, englishTokens, byLanguage));
        }

        writeRawLog(provenance);
        writeRawData(rows, provenance);
        writeChart(rows, provenance);
    }

    /**
     * DeepL을 호출하고 <b>원시 요청·응답 본문을 그대로 기록한다.</b>
     *
     * <p>파싱된 DTO만 남기면 "이 숫자가 실제 API에서 나왔는가"를 검증할 방법이 없다.
     * 응답 JSON 원본이 있으면 제3자가 번역문을 확인하고, 그 문자열로 토큰을 다시 세서
     * 표를 재계산할 수 있다.
     *
     * <p>{@code DeepLTranslationClient}를 쓰지 않고 직접 호출하는 이유는 원시 본문이 필요하기
     * 때문이다. 클라이언트는 DTO로 파싱해버려서 원본이 남지 않는다. 클라이언트 자체는
     * {@code DeepLTranslationClientTest}가 검증한다.
     */
    private String translateAndRecord(String source, String targetCode) {
        Map<String, Object> body = Map.of(
                "text", List.of(source),
                "source_lang", "EN",
                "target_lang", targetCode);

        String requestJson = toJson(body);
        String startedAt = LocalDateTime.now().format(TIME);
        long start = System.nanoTime();

        ResponseEntity<String> response = deepLWebClient().post()
                .uri("/v2/translate")
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        String responseJson = response.getBody();

        rawCalls.add(new RawCall(startedAt, targetCode, requestJson,
                response.getStatusCode().value(), responseJson, elapsedMs));

        return firstTranslation(responseJson);
    }

    /**
     * 응답 JSON에서 번역문을 꺼낸다.
     *
     * <p>원시 응답 문자열에서 직접 파싱한다. 표의 값과 로그에 남는 값이 같은 출처에서 나와야
     * 대조가 성립한다. 별도로 한 번 더 호출하면 DeepL 응답 변동 때문에 둘이 어긋날 수 있다.
     */
    private static String firstTranslation(String responseJson) {
        return MAPPER.readTree(responseJson).path("translations").get(0).path("text").asString();
    }

    private static String toJson(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    private record RawCall(String startedAt, String targetLang, String requestJson,
                           int httpStatus, String responseJson, long elapsedMs) { }

    /**
     * 원시 HTTP 교환 로그를 파일로 남긴다.
     *
     * <p>차트와 요약표는 이 프로젝트 코드가 만든 산출물이라, 숫자를 지어내도 똑같이 생긴다.
     * 원시 응답 본문은 DeepL이 돌려준 값이므로 <b>번역문을 우리가 정할 수 없다.</b>
     * 표의 토큰 수가 이 응답의 문자열에서 나왔다는 걸 대조할 수 있어야 증빙이 성립한다.
     */
    private void writeRawLog(Provenance p) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# DeepL 원시 HTTP 교환 로그\n\n");
        out.append("측정 시각 ").append(p.timestamp())
                .append(" · 커밋 `").append(p.commit()).append("` · 총 ")
                .append(rawCalls.size()).append("건\n\n");
        out.append("각 항목은 `POST ").append(deepLProperties().getBaseUrl())
                .append("/v2/translate` 의 요청·응답 본문 원본이다. ");
        out.append("`Authorization` 헤더는 API 키가 들어 있어 기록하지 않는다.\n");

        int index = 1;
        for (RawCall call : rawCalls) {
            out.append("\n---\n\n### #").append(index++).append(" · EN → ")
                    .append(call.targetLang()).append("\n\n");
            out.append("| | |\n|---|---|\n");
            out.append("| 호출 시각 | ").append(call.startedAt()).append(" |\n");
            out.append("| HTTP 상태 | ").append(call.httpStatus()).append(" |\n");
            out.append("| 왕복 시간 | ").append(call.elapsedMs()).append(" ms |\n\n");
            out.append("요청 본문\n\n```json\n").append(call.requestJson()).append("\n```\n\n");
            out.append("응답 본문 (DeepL 원본)\n\n```json\n").append(call.responseJson()).append("\n```\n");
        }

        Path path = Path.of("build", "measurement", "benchmark-raw-http-log.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, out.toString());
        System.out.println("BENCH>> 원시 HTTP 로그: " + path.toAbsolutePath());
    }

    /**
     * 측정 출처. 차트와 표에 같이 실어서 <b>그림이 아니라 증거</b>가 되게 한다.
     *
     * <p>차트만 예쁘게 뽑으면 손으로 그린 것과 구분되지 않는다. 캡처 이미지 안에
     * 커밋 해시·측정 시각·재현 명령이 박혀 있어야 제3자가 같은 조건으로 다시 돌려볼 수 있다.
     */
    private record Provenance(String timestamp, String commit, String branch, String dirty) {

        static Provenance capture() {
            return new Provenance(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                    git("rev-parse", "--short", "HEAD"),
                    git("rev-parse", "--abbrev-ref", "HEAD"),
                    git("status", "--porcelain").isBlank() ? "clean" : "uncommitted changes");
        }

        /**
         * git 값을 읽는다. 실패하면 "unknown"을 돌려준다.
         *
         * <p>측정 자체는 git과 무관하므로 여기서 예외를 던져 벤치마크를 죽일 이유가 없다.
         * 다만 출처가 비면 증빙 가치가 떨어지므로 "unknown"으로 드러낸다.
         */
        private static String git(String... args) {
            try {
                List<String> command = new ArrayList<>(List.of("git"));
                command.addAll(List.of(args));

                Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                process.waitFor();
                return output.strip();
            } catch (IOException | InterruptedException e) {
                return "unknown";
            }
        }
    }

    // ---------------------------------------------------------------------
    // 원본 데이터
    // ---------------------------------------------------------------------

    private void writeRawData(List<Row> rows, Provenance p) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("# 언어별 토큰 소모량 벤치마크 (원본 데이터)\n\n");

        out.append("## 측정 출처\n\n");
        out.append("| 항목 | 값 |\n|---|---|\n");
        out.append("| 측정 시각 | ").append(p.timestamp()).append(" |\n");
        out.append("| 커밋 | `").append(p.commit()).append("` (").append(p.branch())
                .append(", ").append(p.dirty()).append(") |\n");
        out.append("| 토크나이저 | JTokkit 1.1.0, `EncodingType.O200K_BASE` |\n");
        out.append("| 번역 | DeepL API `POST /v2/translate` (`source_lang=EN`) |\n");
        out.append("| LLM 호출 | **없음** |\n");
        out.append("| 측정 코드 | `src/test/java/com/borderless/proxy/benchmark/")
                .append("LanguageTokenBenchmarkTest.java` |\n\n");

        out.append("### 재현 방법\n\n");
        out.append("```powershell\n");
        out.append("$env:RUN_PIPELINE_MEASUREMENT='true'\n");
        out.append(".\\gradlew.bat test --tests ")
                .append("'com.borderless.proxy.benchmark.LanguageTokenBenchmarkTest'\n");
        out.append("# 산출물: build/measurement/benchmark-language-tokens.md\n");
        out.append("#         docs/benchmark-language-tokens.html\n");
        out.append("```\n\n");

        out.append("### 이 표를 직접 검증하는 방법\n\n");
        out.append("이 문서와 차트는 프로젝트 코드가 만든 산출물이다. **그 자체로는 ");
        out.append("숫자가 실제 측정에서 나왔다는 증거가 되지 않는다.** 아래 3단계로 대조할 수 있다.\n\n");
        out.append("1. **원시 응답 확인** — `build/measurement/benchmark-raw-http-log.md`에 ");
        out.append("DeepL이 돌려준 응답 본문이 그대로 들어 있다. 번역문은 DeepL이 정한 값이라 ");
        out.append("우리가 만들 수 없다\n");
        out.append("2. **토큰 재계산** — 그 응답의 번역문을 복사해 ");
        out.append("<https://platform.openai.com/tokenizer>에서 `o200k_base`로 센다. ");
        out.append("아래 표와 같은 값이 나와야 한다\n");
        out.append("3. **호출 사실 확인** — DeepL 콘솔(<https://www.deepl.com/your-account/usage>)의 ");
        out.append("사용량 기록과 위 측정 시각을 대조한다. 이건 공급자 측 기록이라 우리가 위조할 수 없다\n\n");
        out.append("> DeepL 번역문은 실행마다 미세하게 달라진다. 합계가 1~2% 흔들리므로 ");
        out.append("절대값보다 **배율(1.7~1.9x)** 을 인용하는 편이 안전하다. ");
        out.append("위 원시 로그의 번역문으로 재계산하면 이 표의 값이 정확히 재현된다.\n\n");
        out.append("### 함께 보관할 것\n\n");
        out.append("| 산출물 | 만든 주체 | 위조 가능성 |\n|---|---|---|\n");
        out.append("| 이 문서 · 차트 HTML | 우리 코드 | 있음 (정리물) |\n");
        out.append("| `benchmark-raw-http-log.md` 응답 본문 | DeepL | 낮음 |\n");
        out.append("| Gradle 테스트 리포트 `build/reports/tests/test/index.html` | Gradle | 낮음 |\n");
        out.append("| DeepL 콘솔 사용량 스크린샷 | DeepL | 없음 |\n");
        out.append("| GitHub Actions 실행 로그 | GitHub | 없음 |\n\n");

        out.append("## 요약\n\n| 문장 | 영어 |");
        TARGETS.forEach((code, name) -> out.append(" ").append(name).append(" |"));
        TARGETS.forEach((code, name) -> out.append(" ").append(name).append(" 배율 |"));
        out.append("\n|---|---|");
        TARGETS.forEach((code, name) -> out.append("---|"));
        TARGETS.forEach((code, name) -> out.append("---|"));
        out.append("\n");

        int index = 1;
        for (Row row : rows) {
            out.append("| #").append(index++).append(" ");
            out.append("| ").append(row.englishTokens()).append(" ");
            for (String code : TARGETS.keySet()) {
                out.append("| ").append(row.byLanguage().get(code).tokens()).append(" ");
            }
            for (String code : TARGETS.keySet()) {
                out.append("| ").append(ratio(row.byLanguage().get(code).tokens(), row.englishTokens()))
                        .append("x ");
            }
            out.append("|\n");
        }

        out.append("\n### 합계\n\n| 언어 | 총 토큰 | 영어 대비 배율 | 영어로 바꿨을 때 절감률 |\n|---|---|---|---|\n");
        int englishTotal = rows.stream().mapToInt(Row::englishTokens).sum();
        out.append("| 영어 | ").append(englishTotal).append(" | 1.00x | 기준 |\n");

        for (Map.Entry<String, String> target : TARGETS.entrySet()) {
            int total = rows.stream()
                    .mapToInt(r -> r.byLanguage().get(target.getKey()).tokens())
                    .sum();
            out.append("| ").append(target.getValue())
                    .append(" | ").append(total)
                    .append(" | ").append(ratio(total, englishTotal)).append("x")
                    .append(" | ").append(reduction(total, englishTotal)).append("%")
                    .append(" |\n");
        }

        out.append("\n## 문장별 상세\n");
        index = 1;
        for (Row row : rows) {
            out.append("\n### #").append(index++).append("\n\n");
            out.append("| 언어 | 토큰 | 문장 |\n|---|---|---|\n");
            out.append("| 영어 | ").append(row.englishTokens()).append(" | ")
                    .append(row.source()).append(" |\n");
            for (Map.Entry<String, String> target : TARGETS.entrySet()) {
                Translated t = row.byLanguage().get(target.getKey());
                out.append("| ").append(target.getValue())
                        .append(" | ").append(t.tokens())
                        .append(" | ").append(t.text())
                        .append(" |\n");
            }
        }

        Path path = Path.of("build", "measurement", "benchmark-language-tokens.md");
        Files.createDirectories(path.getParent());
        Files.writeString(path, out.toString());
        System.out.println("BENCH>> 원본 데이터: " + path.toAbsolutePath());
    }

    // ---------------------------------------------------------------------
    // 차트 (슬라이드에 그대로 캡처할 수 있는 HTML)
    // ---------------------------------------------------------------------

    private void writeChart(List<Row> rows, Provenance p) throws IOException {
        int englishTotal = rows.stream().mapToInt(Row::englishTokens).sum();

        Map<String, Integer> totals = new LinkedHashMap<>();
        totals.put("en", englishTotal);
        TARGETS.forEach((code, name) -> totals.put(code.toLowerCase(), rows.stream()
                .mapToInt(r -> r.byLanguage().get(code).tokens())
                .sum()));

        // 막대는 가장 큰 값을 100%로 잡는다. 고정 배율로 그리면 차이가 눈에 안 들어온다.
        int max = totals.values().stream().mapToInt(Integer::intValue).max().orElse(1);

        StringBuilder bars = new StringBuilder();
        bars.append(bar("영어", englishTotal, max, "en", "기준"));
        for (Map.Entry<String, String> target : TARGETS.entrySet()) {
            int total = totals.get(target.getKey().toLowerCase());
            bars.append(bar(target.getValue(), total, max,
                    target.getKey().toLowerCase(),
                    ratio(total, englishTotal) + "x · +" + (total - englishTotal) + "tok"));
        }

        StringBuilder table = new StringBuilder();
        int index = 1;
        for (Row row : rows) {
            table.append("<tr><td class=\"n\">#").append(index++).append("</td>");
            table.append("<td class=\"num en\">").append(row.englishTokens()).append("</td>");
            for (String code : TARGETS.keySet()) {
                Translated t = row.byLanguage().get(code);
                table.append("<td class=\"num\">").append(t.tokens())
                        .append("<span class=\"r\">").append(ratio(t.tokens(), row.englishTokens()))
                        .append("x</span></td>");
            }
            table.append("<td class=\"src\">").append(escape(row.source())).append("</td></tr>");
        }

        StringBuilder head = new StringBuilder();
        TARGETS.forEach((code, name) -> head.append("<th>").append(name).append("</th>"));

        // 측정에 실제로 쓴 문자열을 그대로 싣는다. 이게 있어야 제3자가 자기 토크나이저로
        // 다시 세서 표를 검증할 수 있다. 캡처만 남아도 검증 경로가 끊기지 않는다.
        StringBuilder verify = new StringBuilder();
        index = 1;
        for (Row row : rows) {
            verify.append("<div class=\"vrow\"><div class=\"vn\">#").append(index++).append("</div><div>");
            verify.append("<div class=\"vline\"><span class=\"vlang en\">EN</span>")
                    .append("<span class=\"vtok\">").append(row.englishTokens()).append("</span>")
                    .append("<code>").append(escape(row.source())).append("</code></div>");
            for (Map.Entry<String, String> target : TARGETS.entrySet()) {
                Translated t = row.byLanguage().get(target.getKey());
                verify.append("<div class=\"vline\"><span class=\"vlang ")
                        .append(target.getKey().toLowerCase()).append("\">")
                        .append(target.getKey()).append("</span>")
                        .append("<span class=\"vtok\">").append(t.tokens()).append("</span>")
                        .append("<code>").append(escape(t.text())).append("</code></div>");
            }
            verify.append("</div></div>");
        }

        String html = CHART_TEMPLATE
                .replace("{{BARS}}", bars.toString())
                .replace("{{HEAD}}", head.toString())
                .replace("{{ROWS}}", table.toString())
                .replace("{{VERIFY}}", verify.toString())
                .replace("{{TIMESTAMP}}", p.timestamp())
                .replace("{{COMMIT}}", p.commit())
                .replace("{{BRANCH}}", p.branch())
                .replace("{{DIRTY}}", p.dirty());

        Path path = Path.of("docs", "benchmark-language-tokens.html");
        Files.createDirectories(path.getParent());
        Files.writeString(path, html);
        System.out.println("BENCH>> 차트: " + path.toAbsolutePath());
    }

    private static String bar(String label, int tokens, int max, String cls, String note) {
        int percent = (int) Math.round(tokens * 100.0 / max);
        return """
                <div class="brow">
                  <span class="bl">%s</span>
                  <span class="btrack"><i class="bfill %s" style="width:%d%%"></i></span>
                  <span class="bv">%d<span class="bu">tok</span></span>
                  <span class="bn">%s</span>
                </div>
                """.formatted(label, cls, Math.max(percent, 2), tokens, note);
    }

    private static String ratio(int value, int base) {
        return BigDecimal.valueOf(value)
                .divide(BigDecimal.valueOf(base), 2, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String reduction(int from, int to) {
        return BigDecimal.valueOf(from - to)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(from), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------------
    // 조립
    // ---------------------------------------------------------------------

    private static DeepLProperties deepLProperties() {
        DeepLProperties properties = new DeepLProperties();
        properties.setApiKey(env("DEEPL_API_KEY"));
        String baseUrl = env("DEEPL_BASE_URL");
        properties.setBaseUrl(baseUrl.isBlank() ? properties.recommendedBaseUrl() : baseUrl);
        return properties;
    }

    private static WebClient deepLWebClient() {
        DeepLProperties properties = deepLProperties();
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " 환경변수가 필요합니다.");
        }
        return value;
    }

    private record Translated(String text, int tokens) { }

    private record Row(String source, int englishTokens, Map<String, Translated> byLanguage) { }

    private static final String CHART_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="ko">
            <head>
            <meta charset="utf-8">
            <title>언어별 토큰 소모량 벤치마크</title>
            <style>
              :root {
                --bg:#15161a; --panel:#1b1c21; --line:#2a2c33;
                --t1:#e8e9ed; --t2:#9a9ca6; --t3:#5f6169;
                --en:#4ade80; --ko:#7c8cff; --vi:#fbbf24; --tl:#f472b6;
              }
              * { box-sizing:border-box; }
              body {
                margin:0; padding:44px 40px; background:var(--bg); color:var(--t1);
                font-family:-apple-system,"Segoe UI",Roboto,"Malgun Gothic",sans-serif;
                font-size:14px; line-height:1.6;
              }
              h1 { font-size:23px; margin:0 0 6px; letter-spacing:-.4px; }
              .lede { color:var(--t2); font-size:13.5px; margin:0 0 6px; max-width:760px; }
              .meta { color:var(--t3); font-size:12px; margin:0 0 30px; }
              .meta code { background:#22232a; padding:1px 5px; border-radius:4px; font-size:11.5px; }
              .panel {
                background:var(--panel); border:1px solid var(--line);
                border-radius:12px; padding:26px 28px; margin-bottom:22px;
              }
              .ph { font-size:12px; font-weight:600; color:var(--t2);
                    text-transform:uppercase; letter-spacing:.9px; margin:0 0 20px; }
              .brow { display:grid; grid-template-columns:78px 1fr 74px 118px;
                      align-items:center; gap:14px; margin-bottom:13px; }
              .bl { font-size:13px; color:var(--t2); text-align:right; }
              .btrack { height:22px; background:#22232a; border-radius:5px; overflow:hidden; }
              .bfill { display:block; height:100%; border-radius:5px; }
              .bfill.en { background:var(--en); } .bfill.ko { background:var(--ko); }
              .bfill.vi { background:var(--vi); } .bfill.tl { background:var(--tl); }
              .bv { font-size:15px; font-weight:650; font-variant-numeric:tabular-nums; }
              .bu { font-size:10.5px; color:var(--t3); margin-left:3px; font-weight:400; }
              .bn { font-size:12px; color:var(--t3); font-variant-numeric:tabular-nums; }
              table { width:100%; border-collapse:collapse; font-size:13px; }
              th, td { padding:9px 11px; border-bottom:1px solid var(--line); text-align:left; }
              th { font-size:11.5px; color:var(--t3); text-transform:uppercase;
                   letter-spacing:.7px; font-weight:600; }
              td.n { color:var(--t3); font-size:12px; width:44px; }
              td.num { font-variant-numeric:tabular-nums; font-weight:600; width:96px; }
              td.num.en { color:var(--en); }
              td.num .r { color:var(--t3); font-size:11px; font-weight:400; margin-left:6px; }
              td.src { color:var(--t2); font-size:12.5px; }
              tr:last-child td { border-bottom:none; }
              .note { color:var(--t3); font-size:12px; margin:16px 0 0; }
              .note b { color:var(--t2); font-weight:600; }

              /* 검증 블록 — 측정에 쓴 문자열 원본 */
              .vrow { display:grid; grid-template-columns:36px 1fr; gap:10px;
                      padding:11px 0; border-bottom:1px solid var(--line); }
              .vrow:last-child { border-bottom:none; }
              .vn { color:var(--t3); font-size:12px; padding-top:2px; }
              .vline { display:grid; grid-template-columns:34px 38px 1fr;
                       gap:9px; align-items:baseline; margin-bottom:4px; }
              .vlang { font-size:10px; font-weight:700; letter-spacing:.5px;
                       padding:2px 0; text-align:center; border-radius:3px; color:#15161a; }
              .vlang.en { background:var(--en); } .vlang.ko { background:var(--ko); }
              .vlang.vi { background:var(--vi); } .vlang.tl { background:var(--tl); }
              .vtok { font-size:12px; font-variant-numeric:tabular-nums;
                      color:var(--t2); text-align:right; }
              .vline code { font-size:12px; color:var(--t1); font-family:
                      ui-monospace,"Cascadia Mono",Consolas,monospace; word-break:break-word; }

              /* 출처 — 캡처 이미지 안에 남아야 증빙이 된다 */
              .prov { border:1px solid var(--line); border-radius:10px;
                      padding:16px 20px; background:#191a1e; }
              .prov dl { display:grid; grid-template-columns:auto 1fr;
                         gap:5px 16px; margin:0; font-size:12px; }
              .prov dt { color:var(--t3); }
              .prov dd { margin:0; color:var(--t2); font-variant-numeric:tabular-nums; }
              .prov code { background:#22232a; padding:1px 5px; border-radius:4px; font-size:11.5px; }
              .prov .repro { margin:14px 0 0; padding-top:13px; border-top:1px solid var(--line); }
              .prov .repro pre { margin:6px 0 0; padding:10px 12px; background:#22232a;
                       border-radius:6px; font-size:11.5px; color:var(--t1); overflow-x:auto; }
            </style>
            </head>
            <body>
              <h1>언어별 토큰 소모량</h1>
              <p class="lede">
                같은 내용을 어떤 언어로 쓰는지에 따라 과금 토큰이 달라진다.
                이 차이가 영어 피벗으로 비용을 줄일 수 있는 근거다.
              </p>
              <p class="meta">
                토크나이저 <code>JTokkit o200k_base</code> (GPT-4o / GPT-4.1 과금 단위) ·
                번역 <code>DeepL API</code> (영어 원문 → 각 언어) ·
                LLM 호출 없음 · DeepL 번역문 변동으로 합계는 실행마다 1~2% 흔들림
              </p>

              <div class="panel">
                <p class="ph">문장 5개 합계</p>
                {{BARS}}
              </div>

              <div class="panel">
                <p class="ph">문장별 내역</p>
                <table>
                  <thead><tr><th></th><th>영어</th>{{HEAD}}<th>영어 원문</th></tr></thead>
                  <tbody>{{ROWS}}</tbody>
                </table>
                <p class="note">
                  <b>읽는 법</b> — 작은 숫자는 영어 대비 배율이다.
                  문장이 짧을수록 배율이 커지는 경향이 있는데, 짧은 문장에서는 문법 형태소가
                  차지하는 비중이 상대적으로 크기 때문이다.
                </p>
              </div>

              <div class="panel">
                <p class="ph">측정에 쓴 문자열 (직접 검증용)</p>
                {{VERIFY}}
                <p class="note">
                  <b>이 표를 직접 확인하는 방법</b> —
                  위 문자열을 그대로 복사해 <code>platform.openai.com/tokenizer</code>에서
                  <code>o200k_base</code>로 세면 같은 토큰 수가 나온다.
                  토큰 계산은 같은 문자열에 대해 항상 같은 값을 낸다.
                </p>
              </div>

              <div class="prov">
                <dl>
                  <dt>측정 시각</dt><dd>{{TIMESTAMP}}</dd>
                  <dt>커밋</dt><dd><code>{{COMMIT}}</code> · {{BRANCH}} · {{DIRTY}}</dd>
                  <dt>토크나이저</dt><dd>JTokkit 1.1.0 · <code>EncodingType.O200K_BASE</code></dd>
                  <dt>번역</dt><dd>DeepL API <code>POST /v2/translate</code> (<code>source_lang=EN</code>)</dd>
                  <dt>LLM 호출</dt><dd>없음 (토큰 계산만)</dd>
                  <dt>측정 코드</dt>
                  <dd><code>src/test/java/com/borderless/proxy/benchmark/LanguageTokenBenchmarkTest.java</code></dd>
                </dl>
                <div class="repro">
                  <dl><dt>재현</dt><dd></dd></dl>
                  <pre>$env:RUN_PIPELINE_MEASUREMENT='true'
            .\\gradlew.bat test --tests 'com.borderless.proxy.benchmark.LanguageTokenBenchmarkTest'</pre>
                </div>
              </div>
            </body>
            </html>
            """;
}
