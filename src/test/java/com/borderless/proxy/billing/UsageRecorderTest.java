package com.borderless.proxy.billing;

import com.borderless.proxy.billing.config.BillingProperties;
import com.borderless.proxy.billing.config.ModelPricing;
import com.borderless.proxy.billing.dto.ReportedUsage;
import com.borderless.proxy.billing.dto.UsageRecordRequest;
import com.borderless.proxy.billing.repository.UsageLogRepository;
import com.borderless.proxy.member.entity.Member;
import com.borderless.proxy.member.repository.MemberRepository;
import com.borderless.proxy.proxy.entity.ProxyRequest;
import com.borderless.proxy.proxy.entity.ProxyRequestStatus;
import com.borderless.proxy.proxy.entity.UsageLog;
import com.borderless.proxy.proxy.repository.ProxyRequestRepository;
import com.borderless.proxy.routing.TokenCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("UsageRecorder")
class UsageRecorderTest {

    private static final String MODEL = "test-model";

    /** 검산하기 쉬운 단가. 입력 100만 토큰당 $3, 출력 100만 토큰당 $15 */
    private static final ModelPricing PRICING =
            new ModelPricing(new BigDecimal("3.00"), new BigDecimal("15.00"));

    /** API가 보고한 실측 사용량. 입력 10,000 · 출력 2,000 토큰 */
    private static final ReportedUsage USAGE = new ReportedUsage(10_000, 2_000);

    private static final String ORIGINAL = "우리 회사의 프로젝트 관리 시스템에 대해 자세히 설명해 주세요.";
    private static final String SENT = "Please explain {TERM_01} in detail.";
    private static final String ENGLISH_ANSWER = "The project management system is configured as follows.";
    private static final String NATIVE_ANSWER = "프로젝트 관리 시스템은 다음과 같이 구성되어 있습니다.";

    /** 테스트가 토크나이저 실제 출력에 맞춰 기대값을 계산하도록 같은 계산기를 쓴다. */
    private final TokenCalculator tokenCalculator = new TokenCalculator();

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ProxyRequestRepository proxyRequestRepository;

    @Mock
    private UsageLogRepository usageLogRepository;

    private UsageRecorder usageRecorder;

    @BeforeEach
    void setUp() {
        // 계산기는 실제 객체를 쓴다. 목으로 대체하면 배선만 확인되고 금액 산술이 검증되지 않는다.
        usageRecorder = new UsageRecorder(
                new TokenSavingsCalculator(tokenCalculator),
                new CostCalculator(new BillingProperties(new BigDecimal("0.10"), Map.of(MODEL, PRICING))),
                memberRepository,
                proxyRequestRepository,
                usageLogRepository);

        given(memberRepository.getReferenceById(any())).willReturn(org.mockito.Mockito.mock(Member.class));
        given(proxyRequestRepository.save(any())).willAnswer(call -> call.getArgument(0));
        given(usageLogRepository.save(any())).willAnswer(call -> call.getArgument(0));
    }

    private UsageRecordRequest pivotedRequest() {
        return new UsageRecordRequest(
                1L, ORIGINAL, SENT, ENGLISH_ANSWER, NATIVE_ANSWER,
                "ko", "TIER_2", true, MODEL, USAGE, 1_234);
    }

    private UsageLog record(UsageRecordRequest request) {
        return usageRecorder.record(request);
    }

    @Nested
    @DisplayName("사용량 기록")
    class Recording {

        @Test
        @DisplayName("입력·출력 토큰은 API 보고값을 그대로 저장한다")
        void storesReportedUsageAsIs() {
            UsageLog saved = record(pivotedRequest());

            // 로컬에서 다시 센 값을 넣으면 시스템 프롬프트 오버헤드와 추론 토큰이 빠져 청구액과 어긋난다.
            assertThat(saved.getInputTokens()).isEqualTo(10_000);
            assertThat(saved.getOutputTokens()).isEqualTo(2_000);
        }

        @Test
        @DisplayName("원가는 보고 사용량에 모델 단가를 적용한 값이다")
        void storesActualCostFromReportedUsage() {
            UsageLog saved = record(pivotedRequest());

            // 10,000 × $3/1M + 2,000 × $15/1M = 0.03 + 0.03 = 0.06
            assertThat(saved.getCostUsd()).isEqualByComparingTo("0.060000");
        }

        @Test
        @DisplayName("절감 토큰과 절감액은 입력·출력에 각각의 단가를 적용해 합산한다")
        void storesSavingsWithPerDirectionRates() {
            int savedInput = tokenCalculator.countTokens(ORIGINAL) - tokenCalculator.countTokens(SENT);
            int savedOutput = tokenCalculator.countTokens(NATIVE_ANSWER)
                    - tokenCalculator.countTokens(ENGLISH_ANSWER);

            UsageLog saved = record(pivotedRequest());

            assertThat(saved.getSavedTokens()).isEqualTo(savedInput + savedOutput);

            BigDecimal expected = rate(savedInput, "3.00").add(rate(savedOutput, "15.00"));
            assertThat(saved.getSavedCostUsd()).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("수수료는 절감액의 10%다")
        void storesFeeOnSavings() {
            UsageLog saved = record(pivotedRequest());

            BigDecimal expected = saved.getSavedCostUsd()
                    .multiply(new BigDecimal("0.10"))
                    .setScale(6, java.math.RoundingMode.HALF_UP);
            assertThat(saved.getFeeUsd()).isEqualByComparingTo(expected);
        }

        @Test
        @DisplayName("금액 컬럼은 모두 소수점 6자리로 맞춘다")
        void keepsSixDecimalScale() {
            UsageLog saved = record(pivotedRequest());

            assertThat(saved.getCostUsd().scale()).isEqualTo(6);
            assertThat(saved.getSavedCostUsd().scale()).isEqualTo(6);
            assertThat(saved.getFeeUsd().scale()).isEqualTo(6);
        }

        @Test
        @DisplayName("모델명과 지연 시간을 함께 남긴다")
        void storesModelNameAndLatency() {
            UsageLog saved = record(pivotedRequest());

            assertThat(saved.getModelName()).isEqualTo(MODEL);
            assertThat(saved.getLatencyMs()).isEqualTo(1_234);
        }
    }

    @Nested
    @DisplayName("재번역을 타지 않은 흐름")
    class WithoutRetranslation {

        @Test
        @DisplayName("원어 답변이 null이면 출력 절감을 0으로 둔다")
        void nullNativeAnswerProducesNoOutputSavings() {
            UsageRecordRequest request = new UsageRecordRequest(
                    1L, ORIGINAL, SENT, ENGLISH_ANSWER, null,
                    "en", "TIER_1", false, MODEL, USAGE, 100);

            int savedInput = tokenCalculator.countTokens(ORIGINAL) - tokenCalculator.countTokens(SENT);

            UsageLog saved = record(request);

            // 기준값을 0으로 두면 절감분이 출력 토큰 전체만큼 음수가 되어 지표가 망가진다.
            assertThat(saved.getSavedTokens()).isEqualTo(savedInput);
            assertThat(saved.getSavedCostUsd()).isEqualByComparingTo(rate(savedInput, "3.00"));
        }

        @Test
        @DisplayName("SKIP 흐름처럼 원문과 전송문이 같으면 절감과 수수료가 0이다")
        void identicalTextsProduceZeroSavings() {
            UsageRecordRequest request = new UsageRecordRequest(
                    1L, ORIGINAL, ORIGINAL, ENGLISH_ANSWER, null,
                    "und", "SKIP", false, MODEL, USAGE, 100);

            UsageLog saved = record(request);

            assertThat(saved.getSavedTokens()).isZero();
            assertThat(saved.getSavedCostUsd()).isEqualByComparingTo("0");
            assertThat(saved.getFeeUsd()).isEqualByComparingTo("0");
            // 절감이 없어도 원가는 그대로 기록돼야 한다.
            assertThat(saved.getCostUsd()).isEqualByComparingTo("0.060000");
        }
    }

    @Nested
    @DisplayName("역효과 요청")
    class Backfired {

        @Test
        @DisplayName("전송문이 원문보다 길면 절감액이 음수로 남고 수수료는 0이다")
        void negativeSavingsChargeNoFee() {
            UsageRecordRequest request = new UsageRecordRequest(
                    1L, "짧은 질문", "{TERM_01} {TERM_02} {TERM_03} 짧은 질문을 아주 길게 늘린 문장입니다.",
                    ENGLISH_ANSWER, null, "ko", "TIER_1", false, MODEL, USAGE, 100);

            UsageLog saved = record(request);

            assertThat(saved.getSavedTokens()).isNegative();
            assertThat(saved.getSavedCostUsd()).isNegative();
            // 음수 수수료가 나오면 정산 합계가 조용히 깎여 원인을 찾기 어렵다.
            assertThat(saved.getFeeUsd()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("요청 이력")
    class RequestHistory {

        @Test
        @DisplayName("라우팅 판정과 원문을 SUCCESS 상태로 저장한다")
        void storesRoutingDecision() {
            record(pivotedRequest());

            ArgumentCaptor<ProxyRequest> captor = ArgumentCaptor.forClass(ProxyRequest.class);
            verify(proxyRequestRepository).save(captor.capture());
            ProxyRequest saved = captor.getValue();

            assertThat(saved.getOriginalText()).isEqualTo(ORIGINAL);
            assertThat(saved.getSourceLang()).isEqualTo("ko");
            assertThat(saved.getRouteDecision()).isEqualTo("TIER_2");
            assertThat(saved.getIsPivoted()).isTrue();
            assertThat(saved.getStatus()).isEqualTo(ProxyRequestStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("잘못된 입력")
    class InvalidInput {

        @Test
        @DisplayName("단가가 없는 모델이면 예외를 던지고 아무것도 저장하지 않는다")
        void unknownModelSavesNothing() {
            UsageRecordRequest request = new UsageRecordRequest(
                    1L, ORIGINAL, SENT, ENGLISH_ANSWER, NATIVE_ANSWER,
                    "ko", "TIER_2", true, "unregistered-model", USAGE, 100);

            assertThatThrownBy(() -> record(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unregistered-model");

            // 비용 계산을 저장보다 먼저 수행해야 반쪽 레코드가 남지 않는다.
            verify(proxyRequestRepository, never()).save(any());
            verify(usageLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("request가 null이면 예외를 던진다")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> usageRecorder.record(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /** 토큰 수를 금액으로 환산한다. 절감 토큰이 음수일 수 있어 음수 입력을 허용한다. */
    private static BigDecimal rate(int tokens, String ratePerMillion) {
        return new BigDecimal(ratePerMillion)
                .multiply(BigDecimal.valueOf(tokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, java.math.RoundingMode.HALF_UP);
    }
}
