package com.borderless.proxy.glossary.controller;

import com.borderless.proxy.glossary.dto.GlossaryTermCreateRequestDTO;
import com.borderless.proxy.glossary.dto.GlossaryTermResponseDTO;
import com.borderless.proxy.glossary.service.GlossaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/glossary/terms")
@RequiredArgsConstructor
public class GlossaryController {

    private final GlossaryService glossaryService;

    @GetMapping
    public List<GlossaryTermResponseDTO> getTerms(@AuthenticationPrincipal OAuth2User principal) {
        return glossaryService.getTerms(extractMemberId(principal));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GlossaryTermResponseDTO addTerm(@RequestBody GlossaryTermCreateRequestDTO request,
                                           @AuthenticationPrincipal OAuth2User principal) {
        return glossaryService.addTerm(extractMemberId(principal), request);
    }

    @DeleteMapping("/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(@PathVariable Long termId,
                           @AuthenticationPrincipal OAuth2User principal) {
        glossaryService.deleteTerm(extractMemberId(principal), termId);
    }

    private Long extractMemberId(OAuth2User principal) {
        Long memberId = principal.getAttribute("memberId");
        if (memberId == null) {
            throw new IllegalArgumentException("로그인 사용자 정보를 찾을 수 없습니다.");
        }
        return memberId;
    }
}