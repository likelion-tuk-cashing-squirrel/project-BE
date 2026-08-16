package com.borderless.proxy.glossary.service;

import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.entity.GlossaryTerm;
import com.borderless.proxy.glossary.repository.GlossaryTermRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TermMaskerTest {

    @InjectMocks
    private TermMasker termMasker;

    @Mock
    private GlossaryTermRepository glossaryTermRepository;

    @Test
    @DisplayName("가장 긴 단어가 짧은 단어보다 먼저 마스킹되어 충돌을 방지")
    void maskText_LongestMatchFirst() {
        Long teamId = 1L;
        String originalText = "이번 프로젝트는 스프링부트와 스프링을 활용해 개발합니다.";

        // DB에서 조회될 데이터 생성
        // 의도적으로 짧은 단어("스프링")를 리스트 앞쪽에 배치하여 정렬 로직이 잘 도는지 테스트
        GlossaryTerm term1 = GlossaryTerm.builder()
                .sourceTerm("스프링")
                .maskedToken("{TERM_01}")
                .translation("Spring")
                .build();
        GlossaryTerm term2 = GlossaryTerm.builder()
                .sourceTerm("스프링부트")
                .maskedToken("{TERM_02}")
                .translation("Spring Boot")
                .build();

        List<GlossaryTerm> mockTerms = new ArrayList<>(List.of(term1, term2));
        given(glossaryTermRepository.findAllByTeamId(teamId)).willReturn(mockTerms);

        MaskingResultDTO result = termMasker.maskText(teamId, originalText);

        // "스프링부트"가 먼저 {TERM_02}로 바뀌고, 그 다음 "스프링"이 {TERM_01}로 바뀌었는지 확인
        assertThat(result.getMaskedText()).isEqualTo("이번 프로젝트는 {TERM_02}와 {TERM_01}을 활용해 개발합니다.");

        // 딕셔너리에 두 단어가 모두 잘 들어갔는지 확인
        assertThat(result.getDictionary()).hasSize(2);
        assertThat(result.getDictionary()).containsEntry("{TERM_01}", "Spring");
        assertThat(result.getDictionary()).containsEntry("{TERM_02}", "Spring Boot");
    }
}