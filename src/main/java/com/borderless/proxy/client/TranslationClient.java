package com.borderless.proxy.client;

import com.borderless.proxy.client.dto.TranslationRequest;
import com.borderless.proxy.client.dto.TranslationResponse;
import reactor.core.publisher.Mono;

/**
 * 번역 호출 통로
 * <p>
 * 피벗 번역(KO -> EN -> XX) 여부는 이 인터페이스가 알지 못함
 * 피벗이 필요하면 오케스트레이터가 이 메서드를 두 번 호출
 */
public interface TranslationClient {

    /**
     * @param request 벤더 중립 요청. sourceLang이 null이면 자동 감지
     * @return 요청 texts와 순서·개수가 1:1로 대응하는 번역 결과
     * @throws com.borderless.proxy.client.exception.ExternalApiException 호출 실패 시 (Mono 에러 시그널)
     */
    Mono<TranslationResponse> translate(TranslationRequest request);
}
