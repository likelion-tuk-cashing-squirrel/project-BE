package com.borderless.proxy.prompt;

import com.borderless.proxy.routing.RoutingTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * LLM 시스템 프롬프트 조립 (파이프라인 STEP 04).
 *
 * <p>티어별로 필요한 지시만 넣는다. 시스템 프롬프트는 요청마다 입력 토큰으로 과금되므로,
 * 토큰 절감이 목적인 서비스에서 불필요한 문장을 붙이면 그 자체가 손해다.
 * 지시할 게 없으면 빈 문자열을 돌려주고, 그때 {@code OpenAiChatRequest}는 system 메시지를 아예 생략한다.
 *
 * <p><b>영어 응답 지시가 왜 필요한가.</b> 피벗 흐름은 영어로 번역해 보내고 영어 답변을 받아
 * 원어로 재번역한다. 그런데 지시가 없으면 모델이 사용자 의도를 추론해 원어로 답할 수 있다.
 * 그러면 재번역이 같은 언어를 두 번 번역하는 셈이 되고, 출력 절감 측정
 * ({@code TokenSavingsCalculator.compareOutput})이 같은 언어끼리 비교해 지표가 망가진다.
 *
 * <p><b>마스킹 토큰 지시가 왜 필요한가.</b> LLM은 텍스트를 재생성하므로 치환 토큰을 풀어쓰거나
 * 번역할 수 있다. 그러면 {@code TermRestorer}가 복원하지 못해 사용자 화면에 토큰이 그대로 노출된다.
 * 번역기(DeepL)와 달리 LLM은 원문 보존 보장이 없다.
 */
@Slf4j
@Component
public class SystemPromptBuilder {

    /**
     * 피벗 흐름에서 응답 언어를 고정한다.
     *
     * <p><b>짧게 쓴다.</b> 이전 문구는
     * {@code "Respond in English only, even if the request contains text in another language."}로
     * 15 토큰이었다. 지금은 5 토큰이다. 시스템 프롬프트는 요청마다 입력 토큰으로 과금되는데,
     * 실측에서 지시문 전체(57 토큰)가 영어 피벗으로 아낀 본문 토큰(26)보다 커서 입력 쪽 절감이
     * 오히려 마이너스였다. 근거는 {@code docs/pipeline-measurement.md} 참고.
     *
     * <p>덧붙였던 "even if the request contains text in another language"를 뺀 이유는,
     * 그 조건절이 대비하던 상황이 사라졌기 때문이다. 프롬프트에 남는 비영어 문자열은
     * 마스킹 토큰이었고, 토큰 형식이 {@code {TERM_<PK>}}로 바뀌어 순수 ASCII가 됐다.
     */
    private static final String ENGLISH_ONLY = "Answer in English only.";

    /** 형태소 힌트가 지나치게 길어지면 프롬프트 비용이 커진다. 넘치면 잘라낸다. */
    private static final int MAX_HINT_LENGTH = 1_000;

    /**
     * 티어와 마스킹 결과에 맞는 시스템 프롬프트를 만든다.
     *
     * @param tier          라우터가 결정한 처리 경로
     * @param maskedTokens  실제로 치환된 토큰 목록. {@code MaskingResultDTO.getDictionary().keySet()}을 넘긴다.
     *                      비어 있으면(치환된 용어가 없으면) 토큰 보존 지시를 넣지 않는다
     * @param morphologyHint 형태소 힌트. {@code null}이거나 공백이면 넣지 않는다.
     *                       {@code TIER_3}가 아니면 무시한다
     * @return 시스템 프롬프트. 지시할 게 없으면 빈 문자열
     */
    public String build(RoutingTier tier, Collection<String> maskedTokens, String morphologyHint) {
        if (tier == null) {
            throw new IllegalArgumentException("tier는 null일 수 없습니다.");
        }

        StringJoiner prompt = new StringJoiner("\n");

        if (tier.requiresPivot()) {
            prompt.add(ENGLISH_ONLY);
        }

        String placeholderRule = placeholderRule(tier, maskedTokens);
        if (!placeholderRule.isEmpty()) {
            prompt.add(placeholderRule);
        }

        String hint = morphologyHintLine(tier, morphologyHint);
        if (!hint.isEmpty()) {
            prompt.add(hint);
        }

        String result = prompt.toString();
        log.debug("시스템 프롬프트 생성: tier={}, maskedTokens={}, 길이={}",
                tier, maskedTokens == null ? 0 : maskedTokens.size(), result.length());

        return result;
    }

    /** 형태소 힌트가 없는 흐름을 위한 편의 메서드. */
    public String build(RoutingTier tier, Collection<String> maskedTokens) {
        return build(tier, maskedTokens, null);
    }

    /**
     * 치환 토큰을 그대로 유지하라는 지시를 만든다.
     *
     * <p>토큰 형식을 설명하는 대신 <b>실제 토큰을 나열한다.</b> 예시로 형식을 설명하면 모델이
     * 패턴을 잘못 일반화할 수 있고, {@code GlossaryService}가 생성 규칙을 바꾸면 프롬프트가
     * 조용히 어긋난다.
     *
     * <p><b>지시문을 짧게 쓴다.</b> 이전 문구는
     * {@code "Reproduce these placeholders exactly as they appear, character for character.
     * Do not translate, expand, explain, or reformat them: "}로 토큰 하나 포함 31 토큰이었다.
     * 지금은 9 토큰이다. 요청마다 과금되는 자리라 문장 길이가 그대로 비용이다.
     *
     * <p>치환된 토큰이 없으면 빈 문자열을 돌려준다. 지킬 게 없는데 지시를 넣으면 토큰만 낭비한다.
     */
    private String placeholderRule(RoutingTier tier, Collection<String> maskedTokens) {
        if (!tier.requiresMasking() || maskedTokens == null || maskedTokens.isEmpty()) {
            return "";
        }

        List<String> tokens = maskedTokens.stream()
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .sorted()
                .toList();

        if (tokens.isEmpty()) {
            return "";
        }

        return "Keep verbatim: " + String.join(", ", tokens);
    }

    /**
     * 형태소 힌트를 프롬프트에 넣을 형태로 다듬는다.
     *
     * <p>{@code TIER_3}가 아니면 힌트가 넘어와도 무시한다. 티어 판정과 어긋난 힌트를 넣으면
     * 모델이 없는 문법 구조를 가정할 수 있다.
     */
    private String morphologyHintLine(RoutingTier tier, String morphologyHint) {
        if (!tier.requiresMorphologyHint() || morphologyHint == null || morphologyHint.isBlank()) {
            return "";
        }

        String hint = morphologyHint.strip();
        if (hint.length() > MAX_HINT_LENGTH) {
            log.debug("형태소 힌트가 {}자를 넘어 잘라냅니다. 원본 길이={}", MAX_HINT_LENGTH, hint.length());
            hint = hint.substring(0, MAX_HINT_LENGTH);
        }
        return hint;
    }
}
