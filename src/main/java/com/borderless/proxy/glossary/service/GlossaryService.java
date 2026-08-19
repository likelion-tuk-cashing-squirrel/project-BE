package com.borderless.proxy.glossary.service;

import com.borderless.proxy.glossary.dto.GlossaryTermCreateRequestDTO;
import com.borderless.proxy.glossary.dto.GlossaryTermResponseDTO;
import com.borderless.proxy.glossary.entity.Glossary;
import com.borderless.proxy.glossary.entity.GlossaryTerm;
import com.borderless.proxy.glossary.repository.GlossaryRepository;
import com.borderless.proxy.glossary.repository.GlossaryTermRepository;
import com.borderless.proxy.member.entity.Member;
import com.borderless.proxy.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlossaryService {

    private static final String DEFAULT_NAMESPACE = "default";

    private final GlossaryRepository glossaryRepository;
    private final GlossaryTermRepository glossaryTermRepository;
    private final MemberRepository memberRepository;

    public List<GlossaryTermResponseDTO> getTerms(Long memberId) {
        return glossaryTermRepository.findAllByMemberId(memberId).stream()
                .sorted(Comparator.comparing(GlossaryTerm::getId))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public GlossaryTermResponseDTO addTerm(Long memberId, GlossaryTermCreateRequestDTO request) {
        String sourceTerm = normalize(request.getSourceTerm(), "원문");
        String translation = normalize(request.getTranslation(), "번역문");

        Glossary glossary = glossaryRepository.findByMemberId(memberId)
                .orElseGet(() -> glossaryRepository.save(
                        Glossary.builder()
                                .member(getMember(memberId))
                                .namespace(DEFAULT_NAMESPACE)
                                .description("기본 단어장")
                                .build()
                ));

        glossaryTermRepository.findByGlossaryIdAndSourceTerm(glossary.getId(), sourceTerm)
                .ifPresent(term -> {
                    throw new IllegalArgumentException("이미 등록된 원문 단어입니다.");
                });

        GlossaryTerm saved = glossaryTermRepository.save(
                GlossaryTerm.builder()
                        .glossary(glossary)
                        .sourceTerm(sourceTerm)
                        .translation(translation)
                        .maskedToken(generateMaskedToken())
                        .build()
        );

        return toResponse(saved);
    }

    @Transactional
    public void deleteTerm(Long memberId, Long termId) {
        GlossaryTerm term = glossaryTermRepository.findByIdAndMemberId(termId, memberId)
                .orElseThrow(() -> new IllegalArgumentException("삭제할 단어를 찾을 수 없습니다."));
        glossaryTermRepository.delete(term);
    }

    private GlossaryTermResponseDTO toResponse(GlossaryTerm term) {
        return GlossaryTermResponseDTO.builder()
                .id(term.getId())
                .sourceTerm(term.getSourceTerm())
                .translation(term.getTranslation())
                .build();
    }

    private Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new IllegalArgumentException("회원 정보를 찾을 수 없습니다."));
    }

    private String normalize(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + "은(는) 비어 있을 수 없습니다.");
        }
        return value.trim();
    }

    private String generateMaskedToken() {
        return "{TERM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase() + "}";
    }
}