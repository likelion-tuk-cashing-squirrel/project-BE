package com.borderless.proxy.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml의 app.openai 설정 바인딩
 * <p>
 * apiKey는 반드시 환경변수(OPENAI_API_KEY)로 주입. yaml에 직접 적지 않음
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.openai")
public class OpenAiProperties {

    /** OpenAI API 베이스 URL. ${OPENAI_BASE_URL} */
    private String baseUrl;

    /** ${OPENAI_API_KEY} */
    private String apiKey;

    /** LlmRequest에 model이 없을 때 사용할 기본 모델 ${OPENAI_DEFAULT_MODEL} */
    private String model;

    private Double temperature = 0.3;

    private Integer maxTokens = 2048;

    /** 응답 대기 한계. 초과 시 ExternalApiException으로 변환 */
    private Duration timeout = Duration.ofSeconds(30);

    /** 재시도 최대 횟수. 최초 호출은 미포함 */
    private int maxRetries = 2;
}
