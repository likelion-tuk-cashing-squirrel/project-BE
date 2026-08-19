package com.borderless.proxy.client;

import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.client.exception.ExternalApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepLTranslationClientTest {

    private static final String SUCCESS_BODY = """
            {
              "translations": [
                { "text": "Hello", "detected_source_language": "KO", "billed_characters": 5 }
              ]
            }
            """;

    private MockWebServer server;
    private DeepLProperties properties;
    private DeepLTranslationClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new DeepLProperties();
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setApiKey("test-key:fx");
        properties.setTimeout(Duration.ofMillis(800));
        properties.setMaxRetries(2);

        client = new DeepLTranslationClient(webClient(), properties);
    }

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void enqueue(int status, String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    @Test
    @DisplayName("정상 응답을 TranslationResponse로 변환한다")
    void success() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        TranslationResponse response = client.translate(TranslationRequest.of("안녕하세요", "EN")).block();

        assertThat(response).isNotNull();
        assertThat(response.getFirstText()).isEqualTo("Hello");
        assertThat(response.getTranslations().get(0).getDetectedSourceLang()).isEqualTo("KO");
        assertThat(response.getTotalBilledCharacters()).isEqualTo(5);
        assertThat(response.getLatencyMs()).isNotNegative();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v2/translate");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("DeepL-Auth-Key test-key:fx");
    }

    @Test
    @DisplayName("sourceLang이 없으면 요청 바디에 source_lang이 포함되지 않는다")
    void omitsSourceLang() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        client.translate(TranslationRequest.of("안녕하세요", "EN")).block();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).doesNotContain("source_lang");
        assertThat(body).contains("\"target_lang\":\"EN\"");
        assertThat(body).contains("\"show_billed_characters\":true");
    }

    @Test
    @DisplayName("마스킹 토큰 보호 옵션이 요청 바디에 반영된다")
    void maskedTokenProtection() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        client.translate(TranslationRequest.builder()
                .texts(List.of("<x>{TERM_01}</x>은 좋은 제품입니다"))
                .targetLang("EN")
                .preserveMaskedTokens(true)
                .build()).block();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"tag_handling\":\"xml\"");
        assertThat(body).contains("\"ignore_tags\":[\"x\"]");
    }

    @Test
    @DisplayName("456(할당량 소진)은 재시도하지 않고 즉시 실패한다")
    void quotaExceededIsNotRetried() {
        enqueue(456, "{\"message\":\"Quota exceeded\"}");

        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .satisfies(e -> {
                    ExternalApiException ex = (ExternalApiException) e;
                    assertThat(ex.isQuotaExceeded()).isTrue();
                    assertThat(ex.isRetryable()).isFalse();
                });

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("403(엔드포인트 불일치)은 재시도하지 않는다")
    void wrongEndpointIsNotRetried() {
        enqueue(403, "{\"message\":\"Wrong endpoint. Use https://api-free.deepl.com\"}");

        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Wrong endpoint");

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("429는 재시도 후 성공한다")
    void retriesOnRateLimit() {
        enqueue(429, "{\"message\":\"Too many requests\"}");
        enqueue(200, SUCCESS_BODY);

        TranslationResponse response = client.translate(TranslationRequest.of("안녕", "EN")).block();

        assertThat(response.getFirstText()).isEqualTo("Hello");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("여러 문장의 순서가 유지된다")
    void multipleTexts() {
        enqueue(200, """
                {
                  "translations": [
                    { "text": "First",  "detected_source_language": "KO", "billed_characters": 5 },
                    { "text": "Second", "detected_source_language": "KO", "billed_characters": 6 }
                  ]
                }
                """);

        TranslationResponse response = client.translate(TranslationRequest.builder()
                .texts(List.of("첫째", "둘째"))
                .targetLang("EN")
                .build()).block();

        assertThat(response.getTexts()).containsExactly("First", "Second");
        assertThat(response.getTotalBilledCharacters()).isEqualTo(11);
    }

    @Test
    @DisplayName("API 키가 없으면 네트워크 호출 없이 즉시 실패한다")
    void missingApiKey() {
        properties.setApiKey(null);
        DeepLTranslationClient keyless = new DeepLTranslationClient(webClient(), properties);

        assertThatThrownBy(() -> keyless.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("DEEPL_API_KEY");

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("키 종류와 baseUrl이 어긋나면 감지한다")
    void detectsEndpointMismatch() {
        DeepLProperties freeKeyWithProUrl = new DeepLProperties();
        freeKeyWithProUrl.setApiKey("abc:fx");
        freeKeyWithProUrl.setBaseUrl(DeepLProperties.PRO_BASE_URL);

        assertThat(freeKeyWithProUrl.isFreeKey()).isTrue();
        assertThat(freeKeyWithProUrl.isEndpointMismatched()).isTrue();
        assertThat(freeKeyWithProUrl.recommendedBaseUrl()).isEqualTo(DeepLProperties.FREE_BASE_URL);
    }

    // ---------------------------------------------------------------------
    // 2xx 응답의 규약 위반 (#47)
    // 빈 Mono나 null이 호출부로 새어나가면 원인과 동떨어진 지점에서 NPE가 난다.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("200인데 본문이 비어 있으면 예외로 알린다")
    void emptyBodyFails() {
        enqueue(200, "");

        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("본문이 비어 있습니다");
    }

    @Test
    @DisplayName("translations가 빈 배열이면 예외로 알린다")
    void emptyTranslationsFails() {
        enqueue(200, "{\"translations\":[]}");

        // 그냥 두면 getFirstText()가 null을 반환해 다음 단계에서 터진다.
        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("1건")
                .hasMessageContaining("0건");
    }

    @Test
    @DisplayName("요청한 문장 수와 결과 수가 다르면 예외로 알린다")
    void countMismatchFails() {
        enqueue(200, SUCCESS_BODY); // 결과 1건

        // TranslationResponse는 요청 texts와 순서·개수가 1:1이라는 계약을 전제로 한다.
        assertThatThrownBy(() -> client.translate(TranslationRequest.builder()
                .texts(List.of("첫째", "둘째"))
                .targetLang("EN")
                .build()).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("2건")
                .hasMessageContaining("1건");
    }

    @Test
    @DisplayName("결과 항목에 text가 없으면 예외로 알린다")
    void nullTextFails() {
        enqueue(200, """
                {
                  "translations": [
                    { "detected_source_language": "KO", "billed_characters": 5 }
                  ]
                }
                """);

        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("text가 없습니다");
    }

    @Test
    @DisplayName("규약 위반은 재시도하지 않는다")
    void malformedResponseIsNotRetried() {
        enqueue(200, "{\"translations\":[]}");

        assertThatThrownBy(() -> client.translate(TranslationRequest.of("안녕", "EN")).block())
                .isInstanceOf(ExternalApiException.class)
                .satisfies(e -> assertThat(((ExternalApiException) e).isRetryable()).isFalse());

        // 상태 코드가 502로 표기되지만 재시도 대상이 아니다. 재시도해도 형식이 달라지지 않는다.
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
