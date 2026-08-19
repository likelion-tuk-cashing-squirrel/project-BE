package com.borderless.proxy.client.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 외부 API별 WebClient Bean 정의
 * <p>
 * baseUrl과 인증 헤더를 여기서 한 번만 지정하므로 각 클라이언트 구현체는 경로와 바디만 담당
 * ProxyApplication을 건드리지 않기 위해 @ConfigurationPropertiesScan 대신
 * @EnableConfigurationProperties 사용
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({
        OpenAiProperties.class, DeepLProperties.class, MorphologyProperties.class})
public class WebClientConfig {

    /** LLM 응답이 커질 수 있으므로 기본 256KB보다 넉넉히 설정 */
    private static final int MAX_IN_MEMORY_SIZE = 4 * 1024 * 1024;

    @Bean
    public WebClient openAiWebClient(OpenAiProperties properties) {
        warnIfBlank(properties.getApiKey(), "OPENAI_API_KEY");
        warnIfBlank(properties.getBaseUrl(), "OPENAI_BASE_URL");
        warnIfBlank(properties.getModel(), "OPENAI_DEFAULT_MODEL");

        return baseBuilder(properties.getBaseUrl(), "OpenAI")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();
    }

    @Bean
    public WebClient deepLWebClient(DeepLProperties properties) {
        warnIfBlank(properties.getApiKey(), "DEEPL_API_KEY");
        warnIfBlank(properties.getBaseUrl(), "DEEPL_BASE_URL");
        warnIfEndpointMismatched(properties);

        return baseBuilder(properties.getBaseUrl(), "DeepL")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "DeepL-Auth-Key " + properties.getApiKey())
                .build();
    }

    /**
     * 형태소 사이드카용 WebClient.
     *
     * <p>같은 호스트의 컨테이너로 뜨므로 인증 헤더가 없다. 외부에 노출하려면 인증을 붙여야 한다.
     * baseUrl이 비어 있어도 부팅은 허용한다. TIER_3 요청이 아니면 호출되지 않고,
     * 호출돼도 {@code optional} 설정에 따라 힌트 없이 진행한다.
     */
    @Bean
    public WebClient morphologyWebClient(MorphologyProperties properties) {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            log.warn("MORPHOLOGY_BASE_URL 이 설정되지 않았습니다. "
                    + "TIER_3(타갈로그) 요청은 형태소 힌트 없이 처리됩니다.");
            // baseUrl 없이 build()하면 요청 시점에 IllegalArgumentException이 난다.
            // 클라이언트가 baseUrl 공백을 먼저 검사하므로 여기까지 오지 않지만, 빌드는 되게 둔다.
            return baseBuilder("http://localhost:8000", "Morphology").build();
        }
        return baseBuilder(properties.getBaseUrl(), "Morphology").build();
    }

    private WebClient.Builder baseBuilder(String baseUrl, String vendor) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
                .filter(logRequest(vendor));
    }

    /**
     * 요청 로깅 필터
     * 헤더는 로그에 남기지 않음 (Authorization 헤더에 API 키 포함)
     */
    private ExchangeFilterFunction logRequest(String vendor) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            log.debug("[{}] {} {}", vendor, request.method(), request.url());
            return Mono.just(request);
        });
    }

    /**
     * 키가 없어도 부팅은 허용
     * 외부 API를 쓰지 않는 팀원(오케스트레이터·용어집 등)도 앱을 띄울 수 있어야 하기 때문
     * 실제 호출 시점에는 클라이언트가 즉시 실패 처리
     */
    private void warnIfBlank(String value, String envName) {
        if (value == null || value.isBlank()) {
            log.warn("{} 가 설정되지 않았습니다. 해당 외부 API 호출은 모두 실패합니다. "
                    + ".env 파일 또는 실행 구성의 환경변수를 확인하세요.", envName);
        }
    }

    private void warnIfEndpointMismatched(DeepLProperties properties) {
        if (properties.isEndpointMismatched()) {
            log.warn("""
                    DeepL 키 종류와 baseUrl이 어긋나 있습니다. 이대로면 403 Wrong endpoint 가 발생합니다.
                      현재 baseUrl : {}
                      권장 baseUrl : {}  (키가 ':fx'로 끝나면 무료 키입니다)""",
                    properties.getBaseUrl(), properties.recommendedBaseUrl());
        }
    }
}
