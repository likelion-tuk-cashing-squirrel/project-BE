package com.borderless.proxy.client;

import com.borderless.proxy.client.config.OpenAiProperties;
import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import com.borderless.proxy.client.dto.openai.OpenAiChatRequest;
import com.borderless.proxy.client.dto.openai.OpenAiChatResponse;
import com.borderless.proxy.client.dto.openai.OpenAiErrorResponse;
import com.borderless.proxy.client.exception.ExternalApiException;
import com.borderless.proxy.client.exception.ExternalApiException.Vendor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * OpenAI Chat Completions API 클라이언트
 * <p>
 * POST {base-url}/chat/completions
 */
@Slf4j
@Component
public class OpenAiLlmClient implements LlmClient {

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final Duration MIN_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final OpenAiProperties properties;

    public OpenAiLlmClient(@Qualifier("openAiWebClient") WebClient webClient,
                           OpenAiProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<LlmResponse> complete(LlmRequest request) {
        Mono<LlmResponse> configError = checkConfig(request);
        if (configError != null) {
            return configError;
        }

        OpenAiChatRequest body = OpenAiChatRequest.from(
                request,
                firstNonNull(request.getModel(), properties.getModel()),
                firstNonNull(request.getTemperature(), properties.getTemperature()),
                firstNonNull(request.getMaxTokens(), properties.getMaxTokens()));

        // defer로 감싸야 재시도할 때마다 시작 시각 재측정
        return Mono.defer(() -> {
                    long startedAt = System.nanoTime();
                    return webClient.post()
                            .uri(CHAT_COMPLETIONS_PATH)
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, this::toException)
                            .bodyToMono(OpenAiChatResponse.class)
                            .timeout(properties.getTimeout())
                            .map(response -> response.toLlmResponse(elapsedMs(startedAt)));
                })
                .onErrorMap(TimeoutException.class,
                        e -> ExternalApiException.timeout(Vendor.OPENAI, properties.getTimeout()))
                .onErrorMap(e -> !(e instanceof ExternalApiException),
                        e -> ExternalApiException.network(Vendor.OPENAI, e))
                .retryWhen(retrySpec())
                .doOnError(ExternalApiException.class, this::logError);
    }

    /** 필수 설정값 검증. 누락 시 네트워크로 나가기 전에 차단 */
    private Mono<LlmResponse> checkConfig(LlmRequest request) {
        if (isBlank(properties.getApiKey())) {
            return Mono.error(ExternalApiException.missingConfig(Vendor.OPENAI, "OPENAI_API_KEY"));
        }
        if (isBlank(properties.getBaseUrl())) {
            return Mono.error(ExternalApiException.missingConfig(Vendor.OPENAI, "OPENAI_BASE_URL"));
        }
        if (isBlank(request.getModel()) && isBlank(properties.getModel())) {
            return Mono.error(ExternalApiException.missingConfig(Vendor.OPENAI, "OPENAI_DEFAULT_MODEL"));
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void logError(ExternalApiException e) {
        if (e.isInsufficientQuota()) {
            log.error("OpenAI 크레딧이 부족합니다. 429지만 레이트 리밋이 아니라 결제 문제입니다. "
                    + "platform.openai.com > Billing 에서 크레딧을 충전하세요. {}", e.getMessage());
        } else if (e.isUnauthorized()) {
            log.error("OpenAI API 키가 유효하지 않습니다. OPENAI_API_KEY 를 확인하세요. {}", e.getMessage());
        } else {
            log.warn("OpenAI 호출 실패: {}", e.getMessage());
        }
    }

    /** 에러 응답 바디를 파싱해 ExternalApiException으로 변환 */
    private Mono<? extends Throwable> toException(ClientResponse response) {
        int statusCode = response.statusCode().value();

        return response.bodyToMono(OpenAiErrorResponse.class)
                // 에러 바디가 JSON이 아닐 수 있음 (프록시가 HTML을 반환하는 경우 등)
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty(OpenAiErrorResponse.builder().build())
                .map(error -> ExternalApiException.of(
                        Vendor.OPENAI, statusCode, error.getCodeOrDefault(), error.getMessageOrDefault()));
    }

    private Retry retrySpec() {
        return Retry.backoff(properties.getMaxRetries(), MIN_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.3)
                .filter(this::isRetryable)
                // 재시도가 모두 실패하면 마지막 원인 예외를 그대로 전달 (RetryExhaustedException으로 감싸지 않음)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isRetryable(Throwable throwable) {
        return throwable instanceof ExternalApiException e && e.isRetryable();
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static <T> T firstNonNull(T value, T fallback) {
        return value != null ? value : fallback;
    }
}
