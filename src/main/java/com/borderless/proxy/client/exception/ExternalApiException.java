package com.borderless.proxy.client.exception;

import lombok.Getter;

import java.time.Duration;

/**
 * 외부 API(OpenAI, DeepL) 호출 실패를 나타내는 예외
 * 벤더별 에러 형식 차이를 이 예외 하나로 통일해서 상위 계층에 전달
 * {@code retryable}은 재시도 가능 여부로, 재시도 정책이 이 값만 보고 판단
 */
@Getter
public class ExternalApiException extends RuntimeException {

    /** DeepL 할당량 소진 시 반환되는 비표준 상태 코드 */
    public static final int DEEPL_QUOTA_EXCEEDED = 456;

    /** 네트워크 오류·타임아웃처럼 HTTP 응답 자체가 없는 경우 */
    public static final int NO_RESPONSE = 0;

    /**
     * 성공 응답을 받았지만 본문이 규약과 다른 경우.
     *
     * <p>벤더가 아니라 그 앞단(게이트웨이·프록시)이나 스펙 변경이 원인일 수 있어 502로 표기한다.
     * 상위 계층이 상태 코드만 보고도 "우리 요청 문제가 아니라 상대 응답 문제"로 구분할 수 있다.
     */
    public static final int MALFORMED_RESPONSE = 502;

    /**
     * OpenAI가 크레딧 부족 시 돌려주는 에러 코드
     * 상태 코드는 레이트 리밋과 똑같이 429라서 code로만 구분
     */
    public static final String OPENAI_INSUFFICIENT_QUOTA = "insufficient_quota";

    private final Vendor vendor;
    private final int statusCode;
    private final String errorCode;
    private final boolean retryable;

    private ExternalApiException(Vendor vendor, int statusCode, String errorCode,
                                 boolean retryable, String message) {
        super("[%s] %d - %s".formatted(vendor, statusCode, message));
        this.vendor = vendor;
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    /**
     * HTTP 에러 응답으로부터 예외 생성
     * 429(레이트 리밋)와 5xx(벤더 장애)만 재시도 대상
     * 401/403(인증·엔드포인트 오류)과 456(할당량 소진)은 재시도해도 결과가 같으므로 즉시 실패
     */
    public static ExternalApiException of(Vendor vendor, int statusCode, String message) {
        return of(vendor, statusCode, null, message);
    }

    /**
     * 벤더 에러 코드까지 포함해 예외 생성
     * OpenAI의 insufficient_quota는 429지만 재시도해도 소용없으므로 여기서 필터링
     */
    public static ExternalApiException of(Vendor vendor, int statusCode, String errorCode, String message) {
        return new ExternalApiException(vendor, statusCode, errorCode,
                isRetryable(statusCode, errorCode), message);
    }

    /**
     * 2xx를 받았지만 본문이 비었거나 규약과 다를 때 사용한다.
     *
     * <p>클라이언트 경계에서 이 예외로 바꿔야 한다. 그러지 않으면 빈 {@code Mono}가 호출부까지
     * 흘러가 {@code block()}이 {@code null}을 반환하고, 원인과 동떨어진 지점에서 NPE가 난다.
     *
     * <p><b>재시도하지 않는다.</b> 응답 형식이 규약과 다른 건 재시도로 달라질 문제가 아니고,
     * LLM 호출은 재시도할 때마다 과금된다. 상태 코드가 5xx대라도 재시도 대상에서 제외한다.
     */
    public static ExternalApiException malformedResponse(Vendor vendor, String message) {
        return new ExternalApiException(vendor, MALFORMED_RESPONSE, null, false, message);
    }

    public static ExternalApiException timeout(Vendor vendor, Duration timeout) {
        return new ExternalApiException(vendor, NO_RESPONSE, null, false,
                "응답 시간 초과 (%d초)".formatted(timeout.toSeconds()));
    }

    /** 필수 설정값이 없어 호출 자체를 시도하지 않은 경우 */
    public static ExternalApiException missingConfig(Vendor vendor, String envName) {
        return new ExternalApiException(vendor, NO_RESPONSE, null, false,
                "%s 가 설정되지 않아 호출할 수 없습니다. .env 파일 또는 실행 구성의 환경변수를 확인하세요."
                        .formatted(envName));
    }

    public static ExternalApiException network(Vendor vendor, Throwable cause) {
        ExternalApiException e = new ExternalApiException(vendor, NO_RESPONSE, null, false,
                "네트워크 오류: " + cause.getMessage());
        e.initCause(cause);
        return e;
    }

    private static boolean isRetryable(int statusCode, String errorCode) {
        // DeepL 할당량 소진
        if (statusCode == DEEPL_QUOTA_EXCEEDED) {
            return false;
        }
        // OpenAI 크레딧 부족 - 429지만 재시도해도 동일하게 실패
        if (OPENAI_INSUFFICIENT_QUOTA.equals(errorCode)) {
            return false;
        }
        return statusCode == 429 || statusCode >= 500;
    }

    /** DeepL 무료 할당량이 소진된 상황인지 (알림이 필요한 케이스) */
    public boolean isQuotaExceeded() {
        return vendor == Vendor.DEEPL && statusCode == DEEPL_QUOTA_EXCEEDED;
    }

    /** API 키가 잘못된 상황인지 */
    public boolean isUnauthorized() {
        return statusCode == 401;
    }

    /**
     * OpenAI 크레딧이 부족한 상황인지
     * 429이지만 레이트 리밋이 아니라 결제 문제이므로 대응이 다름
     */
    public boolean isInsufficientQuota() {
        return OPENAI_INSUFFICIENT_QUOTA.equals(errorCode);
    }

    public enum Vendor {
        OPENAI, DEEPL
    }
}
