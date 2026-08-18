package com.borderless.proxy.billing.dto;

/**
 * LLM API가 응답에 실어 보낸 실측 사용량. <b>원가 계산의 유일한 근거다.</b>
 *
 * <p>토큰 수를 우리가 JTokkit으로 다시 세면 안 된다. 세 가지가 어긋난다.
 * <ul>
 *   <li>시스템 프롬프트·함수 스키마·대화 이력처럼 사용자 원문에 없는 토큰이 입력에 더해진다</li>
 *   <li>캐시된 입력 토큰은 할인 단가로 청구되는데 로컬 계산으로는 구분할 수 없다</li>
 *   <li>추론 모델의 내부 추론 토큰은 응답에 내용이 담기지 않아 셀 수 있는 텍스트가 아예 없다.
 *       그런데 출력 토큰으로 그대로 청구된다</li>
 * </ul>
 *
 * <p>그래서 이 값은 반드시 API 응답의 usage 필드에서 그대로 옮겨 담는다.
 * OpenAI Chat Completions면 {@code usage.prompt_tokens}와 {@code usage.completion_tokens}다.
 *
 * @param inputTokens  API가 보고한 입력 토큰 수. 캐시 할인 대상도 포함된 총량
 * @param outputTokens API가 보고한 출력 토큰 수. <b>추론 토큰이 포함된 값이다.</b>
 *                     {@code completion_tokens_details.reasoning_tokens}를 따로 더하면 이중 계상이 된다
 */
public record ReportedUsage(
        int inputTokens,
        int outputTokens
) {

    public ReportedUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens는 음수일 수 없습니다: " + inputTokens);
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens는 음수일 수 없습니다: " + outputTokens);
        }
    }
}
