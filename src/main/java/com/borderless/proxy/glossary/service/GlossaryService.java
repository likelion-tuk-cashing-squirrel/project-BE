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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GlossaryService {

    private static final String DEFAULT_NAMESPACE = "default";

    /**
     * INSERT 시점의 임시 토큰. {@code masked_token}이 {@code nullable = false}라 값이 필요하다.
     * 같은 트랜잭션에서 PK 기반 토큰으로 즉시 덮어쓰므로 DB에 남지 않는다.
     */
    private static final String PENDING_TOKEN = "{TERM_PENDING}";

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

        GlossaryTerm saved = glossaryTermRepository.saveAndFlush(
                GlossaryTerm.builder()
                        .glossary(glossary)
                        .sourceTerm(sourceTerm)
                        .translation(translation)
                        // PK가 확정된 뒤에 토큰을 만들기 위한 임시값. 바로 아래에서 덮어쓴다.
                        .maskedToken(PENDING_TOKEN)
                        .build()
        );
        saved.assignMaskedToken(maskedTokenFor(saved.getId()));

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

    /**
     * 치환 토큰을 만든다. 형식은 {@code {TERM_<PK>}}.
     *
     * <p><b>왜 PK인가.</b> 이 토큰은 요청마다 LLM 프롬프트에 실려 입력 토큰으로 과금된다.
     * 그리고 본문의 치환 자리와 시스템 프롬프트의 "이 토큰을 보존하라" 지시문에
     * <b>각각 들어가 요청당 2번 계산된다.</b>
     *
     * <p>이전 구현은 {@code UUID.randomUUID()} 앞 12자리를 대문자로 잘라 썼는데
     * ({@code {TERM_3F9A2B7C1D0E}}), 16진수 문자열이 o200k_base에서 잘게 쪼개져
     * <b>16 토큰</b>을 먹었다. PK를 쓰면 {@code {TERM_1}}이 <b>5 토큰</b>이다.
     * 실측 기준 용어 하나당 요청당 22 토큰이 줄어든다.
     * 근거는 {@code docs/pipeline-measurement.md} 참고.
     *
     * <p><b>왜 난수가 필요 없나.</b> 이 값은 전역 유일성이 필요한 자리가 아니다.
     * {@code TermMasker}가 한 회원의 용어집 안에서만 치환에 쓰고, PK가 이미 유일하다.
     * 난수 대신 PK를 쓰면 충돌 가능성이 확률적 안전에서 구조적 보장으로 바뀐다.
     *
     * <p><b>{@code TERM_} 접두사와 언더스코어를 유지해야 한다.</b> 형태소 사이드카가
     * {@code \{TERM_[0-9A-Za-z]+\}} 정규식으로 이 토큰을 분석 대상에서 제외한다.
     * 형식을 바꾸면 사이드카가 토큰을 단어로 보고 쪼개 힌트를 오염시킨다.
     *
     * <p>기존에 UUID로 만들어진 토큰은 그대로 둔다. 토큰은 용어별로 저장되고 저장된 값을
     * 그대로 치환에 쓰므로 형식이 섞여도 동작에 문제가 없다. 마이그레이션은 선택 사항이다.
     */
    private static String maskedTokenFor(Long termId) {
        return "{TERM_" + termId + "}";
    }
}