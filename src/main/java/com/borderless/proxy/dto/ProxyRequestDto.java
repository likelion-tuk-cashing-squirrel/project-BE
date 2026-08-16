package com.borderless.proxy.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ProxyRequestDto {

    // 사용자가 화면에서 입력한 텍스트 (질문 또는 번역할 문장)
    private String text;
}