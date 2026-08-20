package com.borderless.proxy.benchmark;

import com.borderless.proxy.routing.TokenCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 발표 슬라이드 숫자 검증용 임시 테스트.
 *
 * <p>{@code build/measurement/benchmark-raw-http-log.md}에 남은 DeepL 응답 문장을 그대로 넣어
 * 토큰 수를 다시 센다. 외부 호출이 없으므로 과금이 없고, 같은 토크나이저라 값이 결정적이다.
 */
@DisplayName("슬라이드 숫자 재계산")
class SlideEvidenceRecountTest {

    private final TokenCalculator tokenCalculator = new TokenCalculator();

    private static final List<String> EN = List.of(
            "The deployment failed.",
            "Please review the pull request before the meeting.",
            "Our team needs a clear migration plan for the database schema changes.",
            "Explain how we should sequence the background worker deployment and the client "
                    + "cutover so that downtime stays under fifteen minutes.",
            "The release owner approved the change, but the rollback steps are still missing "
                    + "from section four of the runbook, and the on-call engineer has not been "
                    + "notified about the maintenance window this weekend.");

    private static final List<String> VI = List.of(
            "Quá trình triển khai đã thất bại.",
            "Xin vui lòng xem xét yêu cầu pull trước khi họp.",
            "Đội ngũ của chúng tôi cần một kế hoạch chuyển đổi rõ ràng cho các thay đổi về cấu trúc cơ sở dữ liệu.",
            "Hãy giải thích cách chúng ta nên sắp xếp thứ tự triển khai máy chủ nền và quá trình "
                    + "chuyển đổi sang hệ thống mới ở phía khách hàng để thời gian ngừng hoạt động "
                    + "không vượt quá mười lăm phút.",
            "Người phụ trách phiên bản đã phê duyệt thay đổi này, nhưng các bước khôi phục vẫn chưa "
                    + "được bổ sung vào phần bốn của sổ tay vận hành, và kỹ sư trực ca vẫn chưa được "
                    + "thông báo về khung thời gian bảo trì vào cuối tuần này.");

    private static final List<String> TL = List.of(
            "Nabigo ang pag-deploy.",
            "Pakisuri ang pull request bago ang pagpupulong.",
            "Kailangan ng aming koponan ng isang malinaw na plano sa migrasyon para sa mga pagbabago sa iskema ng database.",
            "Ipaliwanag kung paano natin dapat isaayos ang pagkakasunod-sunod ng pag-deploy ng "
                    + "background worker at ng paglipat ng kliyente upang manatili sa ilalim ng "
                    + "labinlimang minuto ang downtime.",
            "Inaprubahan ng may-ari ng release ang pagbabago, ngunit nawawala pa rin ang mga hakbang "
                    + "sa pagbalik sa dati sa seksyon apat ng runbook, at hindi pa naabisuhan ang "
                    + "on-call na inhinyero tungkol sa maintenance window ngayong katapusan ng linggo.");

    private static final List<String> KO = List.of(
            "배포에 실패했습니다.",
            "회의 전에 풀 리퀘스트를 검토해 주시기 바랍니다.",
            "저희 팀에는 데이터베이스 스키마 변경을 위한 명확한 마이그레이션 계획이 필요합니다.",
            "가동 중단 시간을 15분 미만으로 유지하기 위해 백그라운드 워커 배포와 클라이언트 전환을 "
                    + "어떤 순서로 진행해야 하는지 설명하십시오.",
            "릴리스 담당자가 변경 사항을 승인했지만, 런북의 4절에는 여전히 롤백 절차가 누락되어 있으며, "
                    + "당직 엔지니어에게는 이번 주말의 유지보수 시간에 대해 통보되지 않았습니다.");

    @Test
    @DisplayName("o200k_base로 다시 세도 벤치마크 표와 같은 값이 나온다")
    void recount() {
        System.out.println();
        System.out.println("=== o200k_base 재계산 (DeepL 응답 문장 그대로, 외부 호출 없음) ===");
        System.out.println();
        System.out.printf("%-6s %6s %6s %6s %6s%n", "문장", "영어", "한국어", "베트남", "타갈로그");

        int enTotal = 0;
        int koTotal = 0;
        int viTotal = 0;
        int tlTotal = 0;

        for (int i = 0; i < EN.size(); i++) {
            int en = tokenCalculator.countTokens(EN.get(i));
            int ko = tokenCalculator.countTokens(KO.get(i));
            int vi = tokenCalculator.countTokens(VI.get(i));
            int tl = tokenCalculator.countTokens(TL.get(i));

            enTotal += en;
            koTotal += ko;
            viTotal += vi;
            tlTotal += tl;

            System.out.printf("#%-5d %6d %6d %6d %6d%n", i + 1, en, ko, vi, tl);
        }

        System.out.println("-".repeat(38));
        System.out.printf("%-6s %6d %6d %6d %6d%n", "합계", enTotal, koTotal, viTotal, tlTotal);
        System.out.println();
        System.out.printf("영어 대비 배율   한국어 %.2fx · 베트남어 %.2fx · 타갈로그 %.2fx%n",
                koTotal / (double) enTotal, viTotal / (double) enTotal, tlTotal / (double) enTotal);
        System.out.printf("영어로 바꿀 때   한국어 -%.1f%% · 베트남어 -%.1f%% · 타갈로그 -%.1f%%%n",
                100.0 * (koTotal - enTotal) / koTotal,
                100.0 * (viTotal - enTotal) / viTotal,
                100.0 * (tlTotal - enTotal) / tlTotal);
        System.out.println();
        System.out.printf("전송 본문 토큰 지수 (베트남어=100)   베트남어 100 → 영어 %.0f%n",
                100.0 * enTotal / viTotal);
        System.out.println();
    }
}
