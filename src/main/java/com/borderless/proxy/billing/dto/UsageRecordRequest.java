package com.borderless.proxy.billing.dto;

/**
 * 파이프라인 한 바퀴가 남긴 산출물. {@code UsageRecorder}가 이걸 받아 비용을 계산하고 기록한다.
 *
 * <p>오케스트레이터가 들고 있는 값을 그대로 옮겨 담는 운반체다. client 패키지의
 * {@code LlmResponse}에 직접 의존하지 않는 이유는, billing 쪽 단위 테스트에서 벤더 응답 객체를
 * 조립하지 않고도 모든 분기를 검증할 수 있어야 하기 때문이다. 변환은 호출부가 한다.
 *
 * @param memberId         요청한 회원 ID
 * @param originalText     사용자가 입력한 원문. 프록시를 안 썼다면 이게 그대로 전송됐을 것이다
 * @param sentText         마스킹·피벗을 거쳐 실제로 LLM에 보낸 프롬프트
 * @param actualAnswerText LLM이 실제로 생성한 답변. 피벗 흐름에서는 영어 답변이다
 * @param nativeAnswerText 원어로 재번역한 답변. 재번역을 타지 않은 흐름(SKIP·TIER_1)에서는
 *                         {@code null}이며, 그때 출력 절감은 0으로 잡힌다
 * @param sourceLang       감지된 출발 언어 ISO 639-1 코드. 특정하지 못했으면 "und"
 * @param routeDecision    라우터가 결정한 티어 이름(SKIP, TIER_1, TIER_2, TIER_3)
 * @param pivoted          영어 피벗을 거쳤는지 여부
 * @param modelName        호출한 모델 이름. LLM 응답이 돌려준 값을 쓴다(스냅샷 이름일 수 있다)
 * @param usage            API가 보고한 실측 사용량. 원가의 유일한 근거
 * @param latencyMs        외부 API 호출에 걸린 시간(ms)
 */
public record UsageRecordRequest(
        Long memberId,
        String originalText,
        String sentText,
        String actualAnswerText,
        String nativeAnswerText,
        String sourceLang,
        String routeDecision,
        boolean pivoted,
        String modelName,
        ReportedUsage usage,
        int latencyMs
) {

    public UsageRecordRequest {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId는 null일 수 없습니다.");
        }
        if (originalText == null) {
            throw new IllegalArgumentException("originalText는 null일 수 없습니다.");
        }
        if (sourceLang == null || sourceLang.isBlank()) {
            throw new IllegalArgumentException("sourceLang은 null이거나 공백일 수 없습니다.");
        }
        if (routeDecision == null || routeDecision.isBlank()) {
            throw new IllegalArgumentException("routeDecision은 null이거나 공백일 수 없습니다.");
        }
        if (usage == null) {
            throw new IllegalArgumentException("usage는 null일 수 없습니다.");
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs는 음수일 수 없습니다: " + latencyMs);
        }
        // sentText·actualAnswerText·nativeAnswerText는 null을 허용한다.
        // TokenCalculator가 null과 공백을 0 토큰으로 세므로 티어별 분기를 호출부가 신경쓸 필요가 없다.
    }
}
