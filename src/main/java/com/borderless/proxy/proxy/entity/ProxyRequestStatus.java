package com.borderless.proxy.proxy.entity;

/**
 * 프록시 요청의 처리 결과.
 *
 * <p>{@code proxy_request.status} 컬럼에 이름 그대로 저장된다(varchar 20).
 * 문자열 리터럴을 직접 쓰면 오타가 조회 조건에서 조용히 빗나가므로 enum으로 고정한다.
 */
public enum ProxyRequestStatus {

    /** 파이프라인이 끝까지 돌아 사용자에게 답변을 돌려준 경우. */
    SUCCESS,

    /** 외부 API 실패 등으로 답변을 만들지 못한 경우. */
    FAILED
}
