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

    /**
     * 조회한 힌트를 LLM 시스템 프롬프트에 실을지 여부.
     *
     * <p><b>기본값 {@code false}. 실측에서 역효과가 확인됐다.</b>
     * 자세한 수치는 {@code docs/pipeline-measurement.md} 참고.
     *
     * <ul>
     *   <li><b>비용</b>: 힌트 문장이 요청당 입력 토큰 162개를 더한다(타갈로그 장문 기준,
     *       보고 입력 141 → 303). 토큰 절감이 목적인 서비스에서 매 요청에 얹는 고정 비용이다</li>
     *   <li><b>품질</b>: 모델이 접사 표기를 어휘로 착각한다. 같은 질문에서 답변에
     *       "you should i + sunod the steps", "Begin by pag + ba + bago the database schema"처럼
     *       힌트 표기가 그대로 섞여 나왔다. 힌트를 끈 쪽은 정상 영어였다</li>
     *   <li><b>구조</b>: 힌트는 STEP 04(LLM) 프롬프트에 실리지만 그 시점의 사용자 프롬프트는
     *       이미 STEP 03에서 영어로 피벗된 문장이다. 모델은 타갈로그 원문을 보지 못하므로
     *       "preserve tense and aspect" 지시의 대상이 프롬프트에 없다. 번역을 수행하는 DeepL은
     *       힌트를 받지 않는다</li>
     * </ul>
     *
     * <p>사이드카와 클라이언트는 그대로 둔다. 힌트 문장 형식(접사 표기를 어휘로 오인하지 않게)과
     * 오탐 필터링(예: {@code namin = nam + pin}, {@code sangay = sang + ay})을 고친 뒤
     * 이 값을 켜서 재측정하면 된다.
     */
    private boolean injectHint = false;
}
