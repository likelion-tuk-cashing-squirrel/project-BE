package com.borderless.proxy.client.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yaml의 app.morphology 설정 바인딩.
 *
 * <p>형태소 분석은 FastAPI 사이드카에서 처리한다. 타갈로그 형태소 분석기가 Python에만 있어
 * (Snowball·Lucene에 타갈로그가 없다) 별도 프로세스로 분리했다.
 *
 * <p>같은 EC2에서 컨테이너로 함께 뜨므로 인증 헤더가 없다. 외부에 노출하려면 인증을 추가해야 한다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.morphology")
public class MorphologyProperties {

    /** ${MORPHOLOGY_BASE_URL}. 비어 있으면 힌트 없이 진행한다. */
    private String baseUrl;

    /**
     * 응답 대기 한계.
     *
     * <p>번역·LLM보다 짧게 잡는다. 힌트는 없어도 파이프라인이 굴러가는 보조 정보인데,
     * 여기서 오래 붙잡히면 전체 응답 시간만 늘어난다.
     */
    private Duration timeout = Duration.ofSeconds(3);

    /**
     * 힌트 조회 실패를 무시할지 여부.
     *
     * <p>기본값 {@code true}. 사이드카가 죽었다고 번역·LLM까지 막을 이유가 없다.
     * 힌트가 없으면 번역 품질이 조금 떨어질 뿐이다.
     */
    private boolean optional = true;
}
