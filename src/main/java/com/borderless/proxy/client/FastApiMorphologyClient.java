package com.borderless.proxy.client;

import com.borderless.proxy.client.config.MorphologyProperties;
import com.borderless.proxy.client.dto.MorphologyHints;
import com.borderless.proxy.client.exception.ExternalApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * FastAPI 형태소 사이드카 클라이언트.
 *
 * <p>POST {base-url}/morphology/hints
 *
 * <p><b>실패를 삼킨다.</b> 다른 외부 API 클라이언트와 다른 점이다. 형태소 힌트는 없어도
 * 파이프라인이 굴러가는 보조 정보라, 사이드카가 죽었다고 번역·LLM까지 막을 이유가 없다.
 * {@code app.morphology.optional}이 꺼져 있으면 예외를 그대로 전파한다.
 *
 * <p>재시도하지 않는다. 힌트 하나 얻으려고 응답 시간을 늘리는 건 이득이 없다.
 */
@Slf4j
@Component
public class FastApiMorphologyClient implements MorphologyClient {

    private static final String HINTS_PATH = "/morphology/hints";

    private final WebClient webClient;
    private final MorphologyProperties properties;

    public FastApiMorphologyClient(@Qualifier("morphologyWebClient") WebClient webClient,
                                   MorphologyProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public Mono<MorphologyHints> hints(String text) {
        if (text == null || text.isBlank()) {
            return Mono.just(MorphologyHints.empty(text));
        }
        if (isBlank(properties.getBaseUrl())) {
            return fallback(text, ExternalApiException.missingConfig(
                    ExternalApiException.Vendor.MORPHOLOGY, "MORPHOLOGY_BASE_URL"));
        }

        return webClient.post()
                .uri(HINTS_PATH)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toException)
                .bodyToMono(MorphologyHints.class)
                .timeout(properties.getTimeout())
                // 2xx인데 본문이 없으면 빈 Mono가 된다. 힌트 없음으로 처리한다.
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.debug("형태소 사이드카가 빈 본문을 반환했습니다. 힌트 없이 진행합니다.");
                    return MorphologyHints.empty(text);
                }))
                .onErrorMap(TimeoutException.class,
                        e -> ExternalApiException.timeout(
                                ExternalApiException.Vendor.MORPHOLOGY, properties.getTimeout()))
                .onErrorMap(e -> !(e instanceof ExternalApiException),
                        e -> ExternalApiException.network(ExternalApiException.Vendor.MORPHOLOGY, e))
                .onErrorResume(ExternalApiException.class, e -> fallback(text, e));
    }

    /**
     * 힌트 조회 실패를 어떻게 다룰지 결정한다.
     *
     * <p>{@code optional}이 켜져 있으면 빈 힌트로 대체하고 진행한다. 사이드카 장애가
     * 서비스 전체 장애로 번지지 않게 하는 게 목적이다. 대신 로그를 남겨 방치되지 않게 한다.
     */
    private Mono<MorphologyHints> fallback(String text, ExternalApiException e) {
        if (!properties.isOptional()) {
            return Mono.error(e);
        }

        log.warn("형태소 힌트를 가져오지 못해 힌트 없이 진행합니다. 번역 품질이 떨어질 수 있습니다. {}",
                e.getMessage());
        return Mono.just(MorphologyHints.empty(text));
    }

    private Mono<? extends Throwable> toException(ClientResponse response) {
        int statusCode = response.statusCode().value();

        return response.bodyToMono(String.class)
                .onErrorResume(e -> Mono.empty())
                .defaultIfEmpty("응답 본문 없음")
                .map(body -> ExternalApiException.of(
                        ExternalApiException.Vendor.MORPHOLOGY, statusCode, body));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
