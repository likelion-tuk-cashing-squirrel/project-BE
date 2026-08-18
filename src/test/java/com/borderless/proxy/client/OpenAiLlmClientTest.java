package com.borderless.proxy.client;

import com.borderless.proxy.client.config.OpenAiProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 OpenAI 대신 로컬 목 서버를 두드려 클라이언트 동작 검증
 */
class OpenAiLlmClientTest {

    private static final String SUCCESS_BODY = """
            {
              "id": "chatcmpl-abc123",
              "object": "chat.completion",
              "model": "gpt-4.1-mini-2025-04-14",
              "choices": [
                { "index": 0,
                  "message": { "role": "assistant", "content": "번역 결과입니다" },
                  "finish_reason": "stop" }
              ],
              "usage": { "prompt_tokens": 120, "completion_tokens": 45, "total_tokens": 165 }
            }
            """;

    private MockWebServer server;
    private OpenAiProperties properties;
    private OpenAiLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        properties = new OpenAiProperties();
        properties.setBaseUrl(server.url("/v1").toString());
        properties.setApiKey("test-api-key");
        properties.setModel("gpt-4.1-mini");
        properties.setTemperature(0.3);
        properties.setMaxTokens(2048);
        properties.setTimeout(Duration.ofMillis(800));
        properties.setMaxRetries(2);

        client = new OpenAiLlmClient(webClient(), properties);
    }

    private WebClient webClient() {
        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
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
    @DisplayName("정상 응답을 LlmResponse로 변환한다")
    void success() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        LlmResponse response = client.complete(LlmRequest.of("안녕하세요")).block();

        assertThat(response).isNotNull();
        assertThat(response.getContent()).isEqualTo("번역 결과입니다");
        assertThat(response.getModel()).isEqualTo("gpt-4.1-mini-2025-04-14");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(120);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(45);
        assertThat(response.getLatencyMs()).isNotNegative();

        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-api-key");
    }

    @Test
    @DisplayName("요청 DTO에 값이 없으면 설정 기본값이 채워져 전송된다")
    void appliesDefaults() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        client.complete(LlmRequest.of("안녕하세요")).block();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"gpt-4.1-mini\"");
        assertThat(body).contains("\"temperature\":0.3");
        assertThat(body).contains("\"max_completion_tokens\":2048");
    }

    @Test
    @DisplayName("요청 DTO에 값이 있으면 설정 기본값을 덮어쓴다")
    void requestOverridesDefaults() throws InterruptedException {
        enqueue(200, SUCCESS_BODY);

        client.complete(LlmRequest.builder()
                .userPrompt("안녕하세요")
                .model("gpt-4.1")
                .temperature(0.9)
                .maxTokens(100)
                .build()).block();

        String body = server.takeRequest().getBody().readUtf8();
        assertThat(body).contains("\"model\":\"gpt-4.1\"");
        assertThat(body).contains("\"temperature\":0.9");
        assertThat(body).contains("\"max_completion_tokens\":100");
    }

    @Test
    @DisplayName("401은 재시도하지 않고 즉시 실패한다")
    void unauthorizedIsNotRetried() {
        enqueue(401, """
                { "error": { "message": "Incorrect API key provided.", "code": "invalid_api_key" } }
                """);

        assertThatThrownBy(() -> client.complete(LlmRequest.of("안녕")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("Incorrect API key provided.");

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("429는 재시도하고, 이후 성공하면 정상 응답을 돌려준다")
    void retriesOnRateLimit() {
        enqueue(429, "{\"error\":{\"message\":\"Rate limit reached\"}}");
        enqueue(200, SUCCESS_BODY);

        LlmResponse response = client.complete(LlmRequest.of("안녕")).block();

        assertThat(response.getContent()).isEqualTo("번역 결과입니다");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("429 insufficient_quota(크레딧 부족)는 재시도하지 않는다 - 레이트 리밋과 구분")
    void insufficientQuotaIsNotRetried() {
        enqueue(429, """
                {
                  "error": {
                    "message": "You exceeded your current quota, please check your plan and billing details.",
                    "type": "insufficient_quota",
                    "param": null,
                    "code": "insufficient_quota"
                  }
                }
                """);

        assertThatThrownBy(() -> client.complete(LlmRequest.of("안녕")).block())
                .isInstanceOf(ExternalApiException.class)
                .satisfies(e -> {
                    ExternalApiException ex = (ExternalApiException) e;
                    assertThat(ex.isInsufficientQuota()).isTrue();
                    assertThat(ex.isRetryable()).isFalse();
                });

        // 재시도했다면 3. 크레딧 부족은 재시도가 무의미하므로 1회만 호출
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("429 rate_limit_exceeded(진짜 레이트 리밋)는 재시도한다")
    void rateLimitIsRetried() {
        enqueue(429, """
                { "error": { "message": "Rate limit reached", "code": "rate_limit_exceeded" } }
                """);
        enqueue(200, SUCCESS_BODY);

        LlmResponse response = client.complete(LlmRequest.of("안녕")).block();

        assertThat(response.getContent()).isEqualTo("번역 결과입니다");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("5xx가 계속되면 최초 1회 + 재시도 2회까지만 호출하고 실패한다")
    void retriesExhausted() {
        enqueue(500, "{\"error\":{\"message\":\"server error\"}}");
        enqueue(500, "{\"error\":{\"message\":\"server error\"}}");
        enqueue(500, "{\"error\":{\"message\":\"server error\"}}");

        assertThatThrownBy(() -> client.complete(LlmRequest.of("안녕")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("500");

        assertThat(server.getRequestCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("응답이 늦으면 타임아웃으로 실패한다")
    void timeout() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(SUCCESS_BODY)
                .setBodyDelay(3, TimeUnit.SECONDS));

        assertThatThrownBy(() -> client.complete(LlmRequest.of("안녕")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("응답 시간 초과");
    }

    @Test
    @DisplayName("에러 바디가 JSON이 아니어도 상태 코드로 예외를 만든다")
    void nonJsonErrorBody() {
        server.enqueue(new MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>Bad Gateway</html>"));
        enqueue(200, SUCCESS_BODY);

        LlmResponse response = client.complete(LlmRequest.of("안녕")).block();

        assertThat(response.getContent()).isEqualTo("번역 결과입니다");
        assertThat(server.getRequestCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("API 키가 없으면 네트워크 호출 없이 즉시 실패한다")
    void missingApiKey() {
        properties.setApiKey("");
        OpenAiLlmClient keyless = new OpenAiLlmClient(webClient(), properties);

        assertThatThrownBy(() -> keyless.complete(LlmRequest.of("안녕")).block())
                .isInstanceOf(ExternalApiException.class)
                .hasMessageContaining("OPENAI_API_KEY");

        assertThat(server.getRequestCount()).isZero();
    }
}
