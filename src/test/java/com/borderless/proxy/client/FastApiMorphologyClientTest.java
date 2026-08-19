package com.borderless.proxy.client;

import com.borderless.proxy.client.config.MorphologyProperties;
import com.borderless.proxy.client.dto.MorphologyHints;
import com.borderless.proxy.client.exception.ExternalApiException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 형태소 사이드카 클라이언트 검증.
 *
 * <p>다른 클라이언트와 달리 실패를 삼키는 게 정상 동작이다. 힌트는 없어도 파이프라인이
 * 굴러가는 보조 정보라, 사이드카 장애가 번역·LLM까지 막으면 안 된다.
 */
@DisplayName("FastApiMorphologyClient")
class FastApiMorphologyClientTest {

    private static final String SUCCESS_BODY = """
            {
              "text": "nagsulat ang {TERM_01}",
              "hint": "Tagalog morphology hints (root + affixes), use them to preserve tense and aspect: nagsulat = nag + sulat",
              "tokens": [
                { "token": "nagsulat", "stem": "sulat", "prefix": "nag",
                  "infix": null, "suffix": null, "reduplication": null }
              ]
            }
            """;

    private MockWebServer server;
    private MorphologyProperties properties;
    private FastApiMorphologyClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new MorphologyProperties();
        properties.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        properties.setTimeout(Duration.ofMillis(800));

        client = new FastApiMorphologyClient(webClient(), properties);
    }

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
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

    @Nested
    @DisplayName("정상 응답")
    class Success {

        @Test
        @DisplayName("힌트와 분해 내역을 파싱한다")
        void parsesHints() {
            enqueue(200, SUCCESS_BODY);

            MorphologyHints hints = client.hints("nagsulat ang {TERM_01}").block();

            assertThat(hints).isNotNull();
            assertThat(hints.hasHint()).isTrue();
            assertThat(hints.getHint()).contains("nagsulat = nag + sulat");
            assertThat(hints.getTokens()).hasSize(1);
            assertThat(hints.getTokens().get(0).getStem()).isEqualTo("sulat");
            assertThat(hints.getTokens().get(0).getPrefix()).isEqualTo("nag");
        }

        @Test
        @DisplayName("텍스트를 JSON 바디의 text 필드로 보낸다")
        void sendsTextInBody() throws InterruptedException {
            enqueue(200, SUCCESS_BODY);

            client.hints("nagsulat").block();

            RecordedRequest recorded = server.takeRequest();
            assertThat(recorded.getPath()).isEqualTo("/morphology/hints");
            assertThat(recorded.getMethod()).isEqualTo("POST");
            assertThat(recorded.getBody().readUtf8()).contains("\"text\":\"nagsulat\"");
        }

        @Test
        @DisplayName("hint가 빈 문자열이면 hasHint가 false다")
        void emptyHintIsDetected() {
            enqueue(200, "{\"text\":\"ang ko\",\"hint\":\"\",\"tokens\":[]}");

            MorphologyHints hints = client.hints("ang ko").block();

            assertThat(hints.hasHint()).isFalse();
            assertThat(hints.getTokens()).isEmpty();
        }
    }

    @Nested
    @DisplayName("호출 생략")
    class SkipsCall {

        @Test
        @DisplayName("빈 텍스트는 네트워크 호출 없이 빈 힌트를 돌려준다")
        void blankTextSkipsCall() {
            MorphologyHints hints = client.hints("   ").block();

            assertThat(hints.hasHint()).isFalse();
            assertThat(server.getRequestCount()).isZero();
        }

        @Test
        @DisplayName("baseUrl이 없으면 호출하지 않고 빈 힌트를 돌려준다")
        void missingBaseUrlSkipsCall() {
            properties.setBaseUrl(null);

            MorphologyHints hints = client.hints("nagsulat").block();

            assertThat(hints.hasHint()).isFalse();
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Nested
    @DisplayName("실패 격리 (optional = true)")
    class OptionalFallback {

        @Test
        @DisplayName("5xx여도 예외 대신 빈 힌트를 돌려준다")
        void serverErrorFallsBack() {
            enqueue(500, "{\"detail\":\"boom\"}");

            MorphologyHints hints = client.hints("nagsulat").block();

            // 사이드카가 죽었다고 번역·LLM까지 막을 이유가 없다.
            assertThat(hints).isNotNull();
            assertThat(hints.hasHint()).isFalse();
            assertThat(hints.getText()).isEqualTo("nagsulat");
        }

        @Test
        @DisplayName("타임아웃도 빈 힌트로 처리한다")
        void timeoutFallsBack() {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(SUCCESS_BODY)
                    .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));

            MorphologyHints hints = client.hints("nagsulat").block();

            assertThat(hints.hasHint()).isFalse();
        }

        @Test
        @DisplayName("200인데 본문이 비어도 빈 힌트로 처리한다")
        void emptyBodyFallsBack() {
            enqueue(200, "");

            MorphologyHints hints = client.hints("nagsulat").block();

            assertThat(hints).isNotNull();
            assertThat(hints.hasHint()).isFalse();
        }

        @Test
        @DisplayName("재시도하지 않는다")
        void doesNotRetry() {
            enqueue(500, "{}");

            client.hints("nagsulat").block();

            // 힌트 하나 얻으려고 응답 시간을 늘리는 건 이득이 없다.
            assertThat(server.getRequestCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("실패 전파 (optional = false)")
    class RequiredMode {

        @Test
        @DisplayName("optional을 끄면 예외를 그대로 던진다")
        void propagatesError() {
            properties.setOptional(false);
            enqueue(500, "{\"detail\":\"boom\"}");

            assertThatThrownBy(() -> client.hints("nagsulat").block())
                    .isInstanceOf(ExternalApiException.class)
                    .satisfies(e -> assertThat(((ExternalApiException) e).getVendor())
                            .isEqualTo(ExternalApiException.Vendor.MORPHOLOGY));
        }

        @Test
        @DisplayName("optional을 끄면 baseUrl 누락도 예외로 알린다")
        void propagatesMissingConfig() {
            properties.setOptional(false);
            properties.setBaseUrl("");

            assertThatThrownBy(() -> client.hints("nagsulat").block())
                    .isInstanceOf(ExternalApiException.class)
                    .hasMessageContaining("MORPHOLOGY_BASE_URL");
        }
    }
}
