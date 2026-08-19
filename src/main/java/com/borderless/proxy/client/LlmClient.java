package com.borderless.proxy.client;

import com.borderless.proxy.client.dto.LlmRequest;
import com.borderless.proxy.client.dto.LlmResponse;
import reactor.core.publisher.Mono;

/**
 * LLM 호출 통로
 * 반환 타입이 Mono이므로 블로킹 여부는 호출측(오케스트레이터)이 결정
 */
public interface LlmClient {

    /**
     * @param request 벤더 중립 요청. model/temperature/maxTokens가 null이면 설정 기본값 적용
     * @return 생성 결과. 토큰 사용량·모델명·지연 시간 포함
     * @throws com.borderless.proxy.client.exception.ExternalApiException 호출 실패 시 (Mono 에러 시그널)
     */
    Mono<LlmResponse> complete(LlmRequest request);
}
