package com.borderless.proxy.client;

import com.borderless.proxy.client.config.DeepLProperties;
import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import com.borderless.proxy.client.dto.deepl.DeepLErrorResponse;
import com.borderless.proxy.client.dto.deepl.DeepLTranslateRequest;
import com.borderless.proxy.client.dto.deepl.DeepLTranslateResponse;
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
 * DeepL 번역 API 클라이언트
 * <p>
 * POST {base-url}/v2/translate
 */
@Slf4j
@Component
public class DeepLTranslationClient implements TranslationClient {

    private static final String TRANSLATE_PATH = "/v2/translate";
    private static final Duration MIN_BACKOFF = Duration.ofMillis(500);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private final WebClient webClient;
    private final DeepLProperties properties;

    public DeepLTranslationClient(@Qualifier("deepLWebClient") WebClient webClient,
                                  DeepLProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<TranslationResponse> translate(TranslationRequest request) {
        if (isBlank(properties.getApiKey())) {
            return Mono.error(ExternalApiException.missingConfig(Vendor.DEEPL, "DEEPL_API_KEY"));
        }
        if (isBlank(properties.getBaseUrl())) {
            return Mono.error(ExternalApiException.missingConfig(Vendor.DEEPL, "DEEPL_BASE_URL"));
        }

        DeepLTranslateRequest body = DeepLTranslateRequest.from(request);

        return Mono.defer(() -> {
                    long startedAt = System.nanoTime();
                    return webClient.post()
                            .uri(TRANSLATE_PATH)
                            .bodyValue(body)
                            .retrieve()
                            .onStatus(HttpStatusCode::isError, this::toException)
                            .bodyToMono(DeepLTranslateResponse.class)
                            .timeout(properties.getTimeout())
                            .map(response -> response.toTranslationResponse(elapsedMs(startedAt)));
                })
                .onErrorMap(TimeoutException.class,
                        e -> ExternalApiException.timeout(Vendor.DEEPL, properties.getTimeout()))
                .onErrorMap(e -> !(e instanceof ExternalApiException),
                        e -> ExternalApiException.network(Vendor.DEEPL, e))
                .retryWhen(retrySpec())
                .doOnError(ExternalApiException.class, this::logError);
    }

    private Mono<? extends Throwable> toException(ClientResponse response) {
        int statusCode = response.statusCode().value();

        return response.bodyToMono(DeepLErrorResponse.class)
                .map(DeepLErrorResponse::getMessageOrDefault)
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty("응답 본문 없음")
                .map(message -> ExternalApiException.of(Vendor.DEEPL, statusCode, message));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void logError(ExternalApiException e) {
        if (e.isQuotaExceeded()) {
            log.error("DeepL 할당량이 소진되었습니다. 더 이상 번역 요청을 처리할 수 없습니다. {}", e.getMessage());
        } else if (e.getStatusCode() == 403) {
            log.error("""
                    DeepL 403 - 엔드포인트가 키 종류와 맞지 않을 수 있습니다.
                      현재 baseUrl : {}
                      권장 baseUrl : {}""",
                    properties.getBaseUrl(), properties.recommendedBaseUrl());
        } else {
            log.warn("DeepL 호출 실패: {}", e.getMessage());
        }
    }

    private Retry retrySpec() {
        return Retry.backoff(properties.getMaxRetries(), MIN_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.3)
                .filter(this::isRetryable)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }

    private boolean isRetryable(Throwable throwable) {
        return throwable instanceof ExternalApiException e && e.isRetryable();
    }

    private int elapsedMs(long startedAtNanos) {
        return (int) ((System.nanoTime() - startedAtNanos) / 1_000_000L);
    }
}
