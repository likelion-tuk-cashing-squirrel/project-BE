package com.borderless.proxy.controller;

import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.dto.ProxyResponseDto;
import com.borderless.proxy.service.ProxyOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyController {

    // 두뇌(오케스트레이터)를 데려온다
    private final ProxyOrchestrator proxyOrchestrator;

    // 프론트엔드가 POST /api/proxy 로 요청을 보내면 이 함수가 받는다
    @PostMapping
    public ProxyResponseDto proxy(@RequestBody ProxyRequestDto request,
                                  @AuthenticationPrincipal OAuth2User principal) {
        // 받은 요청을 두뇌에게 넘기고, 처리 결과를 그대로 돌려준다
        Long memberId = principal.getAttribute("memberId");
        return proxyOrchestrator.process(request, memberId);
    }
}