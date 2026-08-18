package com.borderless.proxy.billing;

import com.borderless.proxy.billing.dto.TokenDelta;
import com.borderless.proxy.routing.TokenCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 프록시가 입력과 출력에서 각각 토큰을 얼마나 줄였는지 측정한다.
 *
 * <p>라우팅에서 쓰는 {@link TokenCalculator}를 그대로 재사용한다. 여기서만 다른 인코딩을 쓰면
 * 같은 요청의 토큰 수가 라우팅 로그와 과금 기록에서 다르게 남아 정산 근거를 신뢰할 수 없게 된다.
 *
 * <p>입력과 출력을 따로 받는 메서드로 나눠뒀다. 문자열 네 개를 한 번에 받으면 호출부에서 순서가
 * 바뀌어도 컴파일러가 잡아주지 못하고, 기준값과 실제값이 뒤바뀌면 절감분 부호가 그대로 뒤집힌다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenSavingsCalculator {

    private final TokenCalculator tokenCalculator;

    /**
     * 입력 쪽 절감을 측정한다.
     *
     * @param originalText 사용자가 입력한 원문. 프록시를 안 썼다면 이게 그대로 전송됐을 것이다
     * @param sentText     마스킹·피벗을 거쳐 실제로 LLM에 보낸 텍스트
     * @return 기준·실제 토큰 수 쌍. {@code null}이나 공백은 0 토큰으로 센다
     */
    public TokenDelta compareInput(String originalText, String sentText) {
        return delta("입력", tokenCalculator.countTokens(originalText), tokenCalculator.countTokens(sentText));
    }

    /**
     * 출력 쪽 절감을 측정한다.
     *
     * <p>기준값으로 재번역된 원어 답변을 쓴다. 원어로 물었다면 모델이 그 언어로 답했을 테니,
     * 같은 내용을 원어로 표현했을 때의 토큰 수가 회피한 출력량의 추정치가 된다.
     * 네이티브 생성 답변과 번역문이 똑같지는 않으므로 정확한 반사실이 아니라 추정이다.
     *
     * @param nativeAnswerText 원어로 재번역한 최종 답변.
     *                         {@code null}이거나 공백이면 재번역을 안 한 흐름으로 보고 출력 절감을 0으로 둔다
     * @param actualAnswerText LLM이 실제로 생성한 답변. 피벗 흐름에서는 영어 답변이다
     */
    public TokenDelta compareOutput(String nativeAnswerText, String actualAnswerText) {
        int actualTokens = tokenCalculator.countTokens(actualAnswerText);

        int baselineTokens = tokenCalculator.countTokens(nativeAnswerText);
        if (baselineTokens == 0) {
            // 재번역 단계를 타지 않은 요청(SKIP·TIER_1)은 비교 대상이 없다.
            // 기준값을 0으로 두면 절감분이 출력 토큰 전체만큼 음수가 되어 지표가 망가진다.
            return TokenDelta.unchanged(actualTokens);
        }

        return delta("출력", baselineTokens, actualTokens);
    }

    private static TokenDelta delta(String label, int baselineTokens, int actualTokens) {
        TokenDelta result = new TokenDelta(baselineTokens, actualTokens);

        if (result.saved() < 0) {
            // 파이프라인이 역효과를 낸 경우다. 용어집 치환 토큰이 원래 용어보다 길거나
            // 피벗·재번역이 원문보다 장황해졌을 때 발생한다. 임계치·용어집 튜닝 근거가 되니 남긴다.
            log.debug("{} 토큰이 프록시를 거쳐 오히려 늘었습니다. baseline={}, actual={}",
                    label, baselineTokens, actualTokens);
        }

        return result;
    }
}
