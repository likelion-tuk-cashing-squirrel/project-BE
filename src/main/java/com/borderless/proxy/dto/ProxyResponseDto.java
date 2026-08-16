package com.borderless.proxy.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProxyResponseDto {

    // AI가 최종적으로 만들어낸 응답 텍스트
    private String result;

    // 이번 요청에 사용된 예상 토큰 수 (비용 확인용)
    private int usedTokens;

    // 영어 피벗을 거쳤는지 여부 (true면 영어 경유, false면 한국어 직행)
    private boolean pivoted;
}