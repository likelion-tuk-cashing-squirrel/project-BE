package com.borderless.proxy.billing;

import com.borderless.proxy.billing.dto.CostBreakdown;
import com.borderless.proxy.billing.dto.TokenComparison;
import com.borderless.proxy.billing.dto.TokenDelta;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.repository.UsageLogRepository;
import com.borderless.proxy.member.repository.MemberRepository;
import com.borderless.proxy.proxy.entity.ProxyRequest;
import com.borderless.proxy.proxy.entity.ProxyRequestStatus;
import com.borderless.proxy.proxy.entity.UsageLog;
import com.borderless.proxy.proxy.repository.ProxyRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 산출물을 받아 절감분과 비용을 계산하고 {@code proxy_request} · {@code usage_log}에 기록한다.
 *
 * <p>오케스트레이터에서 이 로직을 분리한 이유는 두 가지다.
 * <ul>
 *   <li>오케스트레이터는 텍스트 변환 흐름만 지휘하고, 과금은 그 흐름과 독립적으로 검증할 수 있어야 한다</li>
 *   <li>기록이 실패해도 사용자 응답은 이미 만들어져 있다. 호출부가 이 메서드만 감싸면 실패를 격리할 수 있다</li>
 * </ul>
 *
 * <p><b>호출부는 반드시 예외를 격리할 것.</b> 단가가 등록되지 않은 모델이면
 * {@code pricingFor}가 {@link IllegalArgumentException}을 던진다. 그걸 그대로 흘리면
 * 이미 과금이 끝나고 답변까지 나온 요청이 500으로 죽는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageRecorder {

    private final TokenSavingsCalculator savingsCalculator;
    private final CostCalculator costCalculator;
    private final MemberRepository memberRepository;
    private final ProxyRequestRepository proxyRequestRepository;
    private final UsageLogRepository usageLogRepository;

    /**
     * 요청 한 건의 사용량과 비용을 기록한다.
     *
     * @param request 파이프라인이 남긴 텍스트 쌍과 실측 사용량
     * @return 저장된 사용 기록
     * @throws IllegalArgumentException 모델 단가가 등록되지 않았거나 인자가 규칙에 어긋날 때
     */
    @Transactional
    public UsageLog record(UsageRecordRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request는 null일 수 없습니다.");
        }

        CostBreakdown cost = calculateCost(request);
        ProxyRequest saved = saveRequest(request);

        UsageLog usageLog = usageLogRepository.save(UsageLog.builder()
                .request(saved)
                .inputTokens(request.usage().inputTokens())
                .outputTokens(request.usage().outputTokens())
                .savedTokens(cost.savedTokens())
                // 원가만 담는다. 수수료를 더한 청구액은 UsageLog.billedUsd()로 파생한다.
                .costUsd(cost.actualCostUsd())
                .savedCostUsd(cost.savedCostUsd())
                .feeUsd(cost.feeUsd())
                .latencyMs(request.latencyMs())
                .modelName(cost.modelName())
                .build());

        log.debug("사용 기록 저장: requestId={}, usageLogId={}, model={}, savedTokens={}, costUsd={}, feeUsd={}",
                saved.getId(), usageLog.getId(), usageLog.getModelName(),
                usageLog.getSavedTokens(), usageLog.getCostUsd(), usageLog.getFeeUsd());

        return usageLog;
    }

    /**
     * 절감분을 측정하고 금액으로 환산한다.
     *
     * <p>입력은 원문과 실제 전송문을 비교한다. 출력은 재번역된 원어 답변을 기준값으로 삼는다.
     * 재번역을 타지 않은 흐름은 {@code nativeAnswerText}가 {@code null}이라
     * {@code compareOutput}이 절감 0으로 처리한다.
     *
     * <p><b>DeepL 번역 비용은 차감하지 않는다. 미결 사항이 아니라 결정이다.</b>
     * 이 지표가 재는 대상은 "사용자가 LLM 토큰을 얼마나 아꼈는가"이고, DeepL 요금은 그 절감을
     * 만들기 위한 우리 쪽 원가다. 사용자에게 보여주는 절감 지표에 인프라 비용을 섞으면
     * 지표의 의미가 흐려진다. 규모도 작다. 실측에서 타갈로그 장문 기준 DeepL 문자 수가
     * 요청당 약 1,900자로, 같은 요청 토큰 절감액의 6~7% 수준이었다.
     *
     * <p>따라서 {@code savedCostUsd}는 <b>순이익이 아니다.</b> 수수료율 정책은 이 점을
     * 전제로 삼아야 하고, 마진 관리는 별도 지표의 몫이다.
     * 근거는 {@code docs/pipeline-measurement.md} 참고.
     */
    private CostBreakdown calculateCost(UsageRecordRequest request) {
        TokenDelta input = savingsCalculator.compareInput(request.originalText(), request.sentText());
        TokenDelta output = savingsCalculator.compareOutput(
                request.nativeAnswerText(), request.actualAnswerText());

        return costCalculator.calculate(
                request.modelName(), request.usage(), new TokenComparison(input, output));
    }

    /**
     * 요청 이력을 저장한다.
     *
     * <p>회원은 {@code getReferenceById}로 프록시만 받는다. 여기서 회원을 조회할 이유가 없고,
     * {@code member_id}는 FK 제약이 없는 컬럼이라 참조만으로 저장된다.
     */
    private ProxyRequest saveRequest(UsageRecordRequest request) {
        return proxyRequestRepository.save(ProxyRequest.builder()
                .member(memberRepository.getReferenceById(request.memberId()))
                .originalText(request.originalText())
                .sourceLang(request.sourceLang())
                .isPivoted(request.pivoted())
                .routeDecision(request.routeDecision())
                // 이 메서드는 답변이 만들어진 뒤에만 호출된다. 실패 이력 적재는 별도 경로로 다룬다.
                .status(ProxyRequestStatus.SUCCESS)
                .build());
    }
}
