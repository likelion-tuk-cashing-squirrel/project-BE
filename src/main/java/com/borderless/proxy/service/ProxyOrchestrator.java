package com.borderless.proxy.service;

import com.borderless.proxy.dto.ProxyRequestDto;
import com.borderless.proxy.dto.ProxyResponseDto;
import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.service.TermMasker;
import com.borderless.proxy.glossary.service.TermRestorer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProxyOrchestrator {

    // 셰프가 부릴 담당자들 (스프링이 자동으로 넣어줌)
    private final TermMasker termMasker;
    private final TermRestorer termRestorer;

    // 손님 주문(요청)이 들어오면 이 함수가 전체 흐름을 지휘한다
    public ProxyResponseDto process(ProxyRequestDto request) {

        // 손님이 입력한 원본 텍스트
        String originalText = request.getText();

        // 팀 번호 (지금은 임시로 1번 팀으로 고정. 나중에 로그인 정보에서 가져올 예정)
        Long teamId = 1L;

        // ===== 1. 라우팅 판별 =====
        // TODO: 라우팅(CostRouter) 담당 코드가 develop에 병합되면 여기 연결
        //       한국어 직행(Direct)인지 영어 피벗(Pivot)인지 결정

        // ===== 2. 마스킹 =====
        // 고유명사를 {TERM_01} 같은 토큰으로 가린다
        MaskingResultDTO masked = termMasker.maskText(teamId, originalText);
        String maskedText = masked.getMaskedText();       // 가려진 텍스트
        var dictionary = masked.getDictionary();          // 나중에 되돌릴 사전

        // ===== 3. 번역 =====
        // TODO: 번역(TranslationClient) 담당 코드가 병합되면 여기 연결
        //       (피벗일 때만 영어로 번역)

        // ===== 4. LLM 호출 =====
        // TODO: LLM(LlmClient) 담당 코드가 병합되면 여기 연결
        //       지금은 임시로 마스킹된 텍스트를 그대로 결과처럼 사용
        String llmResult = maskedText;

        // ===== 5. 마스킹 복원 =====
        // 가렸던 토큰을 다시 원래 단어로 되돌린다
        String finalResult = termRestorer.restoreText(llmResult, dictionary);

        // ===== 최종 응답 만들기 =====
        return ProxyResponseDto.builder()
                .result(finalResult)   // 최종 결과 텍스트
                .usedTokens(0)         // TODO: 라우팅 연결되면 실제 토큰 수 넣기
                .pivoted(false)        // TODO: 라우팅 연결되면 실제 피벗 여부 넣기
                .build();
    }
}