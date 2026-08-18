package com.borderless.proxy.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml의 app.deepl 설정 바인딩
 * <p>
 * DeepL은 키 종류에 따라 사용해야 하는 엔드포인트가 다름
 * 무료(Developer) 키는 ":fx"로 끝나며 api-free.deepl.com 사용
 * 키와 엔드포인트가 어긋나면 403 "Wrong endpoint" 발생
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.deepl")
public class DeepLProperties {

    /** 무료(Developer) 키를 식별하는 접미사 */
    public static final String FREE_KEY_SUFFIX = ":fx";

    /** 무료(Developer) 키용 엔드포인트 */
    public static final String FREE_BASE_URL = "https://api-free.deepl.com";

    /** 유료 키용 엔드포인트 */
    public static final String PRO_BASE_URL = "https://api.deepl.com";

    /** ${DEEPL_BASE_URL} */
    private String baseUrl;

    /** ${DEEPL_API_KEY} */
    private String apiKey;

    /** 응답 대기 한계. 초과 시 ExternalApiException으로 변환 */
    private Duration timeout = Duration.ofSeconds(10);

    /** 재시도 최대 횟수. 최초 호출은 미포함 */
    private int maxRetries = 2;

    /** 설정된 키가 무료(Developer) 키인지 판별 */
    public boolean isFreeKey() {
        return apiKey != null && apiKey.endsWith(FREE_KEY_SUFFIX);
    }

    /** 키 종류와 baseUrl이 어긋났는지 판별. 어긋난 상태로 호출 시 403 발생 */
    public boolean isEndpointMismatched() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return isFreeKey() != baseUrl.contains("api-free.");
    }

    /** 설정된 키 종류에 맞는 baseUrl 반환 */
    public String recommendedBaseUrl() {
        return isFreeKey() ? FREE_BASE_URL : PRO_BASE_URL;
    }
}
