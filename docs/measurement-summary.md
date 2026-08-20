# 실측 결과 종합 정리

> 측정일: 2026-08-20
> 모델: gpt-4.1-mini-2025-04-14 · 번역: DeepL API Free + TranslateGemma 4B (Ollama)
> 재현: `build/measurement/` 아래 원본 데이터 전부 남아 있음

---

## 1. 언어별 토큰 비효율 (왜 프록시가 필요한가)

같은 의미의 문장 5개를 DeepL로 번역 후 `o200k_base`로 계산. **LLM 호출 없음.**

| 언어 | 합계 토큰 | 영어 대비 배율 |
|---|---:|---:|
| 영어 | 86 | 1.00x |
| 한국어 | 146 | 1.70x |
| 베트남어 | 160 | **1.86x** |

**근거**: `build/measurement/benchmark-language-tokens.md` (문장별 상세)
**검증 방법**: `benchmark-raw-http-log.md`에 DeepL 응답 원본이 있다. 그 번역문을 https://platform.openai.com/tokenizer에서 `o200k_base`로 세면 위 표와 동일하게 나온다.

---

## 2. 피벗 시 입력 토큰 절감

영어로 바꾸면 같은 질문이 더 짧아진다. 토크나이저 계산이라 **노이즈 0**.

| 언어 | 원문 | 피벗 후 | 변화 |
|---|---:|---:|---:|
| 베트남어 | 104 | 60~80 | **-23~42%** |

**근거**: `tier-remeasure.md` + `controlled-experiment.md` D 셀 + `pivot-quality-gemma.md`

---

## 3. 피벗 시 출력 토큰 절감 (핵심 발견)

같은 질문에 대해 원어 직행 vs 영어 피벗 → 재번역 비교.

| 언어 | 원어 직행 출력 | 피벗 출력 | 절감 |
|---|---:|---:|---:|
| 베트남어 (DeepL) | 849 | 455 | **-46%** |
| 베트남어 (TranslateGemma) | 1108 | 388 | **-65%** |

**왜 이런 차이가 나는가**:
- 모델이 원어로 답하면 같은 내용을 더 길게 씀 (토큰 효율이 1.86x 나쁘니까)
- 영어로 답하면 간결하게 씀 (학습 데이터 편향 + 토큰 효율)
- 출력 단가가 입력의 4배($1.60 vs $0.40/1M)라 여기서 돈이 걸림

**근거**: `pivot-quality.md` + `pivot-quality-gemma.md`
**판단 기준**: OpenAI API가 보고한 `usage.completion_tokens` 값을 그대로 사용

---

## 4. 피벗 시 답변 품질 향상

같은 질문, 같은 모델. 답변 구조를 나란히 비교.

| 항목 | 원어 직행 | 피벗 |
|---|---|---|
| 답변 구조 | 부연 많고 반복적 | **단계별 정리 + 타임라인 표** |
| 기술 정확도 | 동등 | 동등 |
| 답변 길이 | 길다 (849~1108 토큰) | 짧다 (388~507 토큰) |
| 가독성 | 네이티브 자연어 | 재번역도 자연스러움 |

**판단 근거**: 답변 전문이 `pivot-quality.md`와 `pivot-quality-gemma.md`에 있음.
구체적으로 본 것:
- 영어 답변은 "1. Schema → 2. Backend → 3. Client → Timeline Table"로 구조화
- 원어 답변은 같은 내용을 풀어쓰면서 반복이 잦음
- 기술 용어 오류는 양쪽 다 없음

---

## 5. 절감 원인 분리 (통제 실험)

변수를 하나씩만 바꿔 3회 반복. 같은 베트남어 질문 사용.

| 셀 | 바꾼 것 | 출력 토큰 | 기준 대비 |
|---|---|---:|---:|
| A 기준선 | 없음 | 868 | 기준 |
| B 프롬프트만 | 시스템 프롬프트 | 577 | **-33.5%** |
| C 마스킹만 | 용어 치환 | 682 | **-21.5%** |
| D 피벗만 | 영어 번역 | 756 | -12.9% (노이즈 범위) |
| E 전부 (TIER_2) | 전부 | 422 | **-51.3%** |

**결론**:
- 프롬프트 최적화가 가장 큰 단독 효과 (-33.5%)
- 마스킹도 유의미 (-21.5%)
- 피벗 단독은 노이즈에 묻힘 (-12.9%, 편차 152토큰)
- **조합하면 시너지** (-51.3%는 개별 합보다 큼)

**근거**: `controlled-experiment.md` (셀별 3회 반복값 + 전송 본문 + 답변 전문)

---

## 6. 티어별 재측정 (현재 코드, 형태소 꺼짐)

| 티어 | 언어 | 입력 변화 | 출력 변화 | 비용 변화 |
|---|---|---:|---:|---:|
| SKIP | 영어 | 0% | +12.9% | +12.7% |
| TIER_1 | 영어 | +20.0% | **-46.7%** | **-45.3%** |
| TIER_2 | 베트남어 | **-23.1%** | **-68.6%** | **-67.5%** |

**근거**: `tier-remeasure.md`
**토큰 수 출처**: OpenAI API 응답의 `usage.prompt_tokens`와 `usage.completion_tokens`

---

## 7. 시스템 프롬프트 개선

| 항목 | 이전 | 현재 | 지시 준수율 |
|---|---:|---:|---|
| 프롬프트 토큰 | 46 | 14 | 3/3 동일 |
| 보고 입력 토큰 | 118 | 86 | - |

**근거**: `prompt-length-ab.md`

---

## 8. 마스킹 토큰 개선

| 형식 | 토큰 수 | 요청당 (본문+지시 2번) |
|---|---:|---:|
| `{TERM_3F9A2B7C1D0E}` (이전) | 16 | 32 |
| `{TERM_1}` (현재) | 5 | 10 |

**근거**: `docs/pipeline-measurement.md` "마스킹 토큰 형식이 비싸다" 절

---

## 9. TranslateGemma 검증

DeepL을 대체할 수 있는지 테스트.

| 항목 | DeepL | TranslateGemma 4B |
|---|---|---|
| 토큰 보존 `{TERM_n}` | 9/9 | **9/9** |
| 기술 용어 정확도 | 최고 | **DeepL급** |
| 대상 언어 정확도 | 항상 정확 | **9/9** (프롬프트 형식 준수 시) |
| 비용 | $0.0765/요청 | **$0** (로컬 GPU) |
| 속도 | 수백ms | 0.6~1.3s (RTX 5080) |

**근거**: `build/mt-test/gemma-fix-result.txt` (3방향 × 3회 = 9건 전부 PASS)

---

## 10. 형태소 힌트 제외 근거

| 항목 | 값 |
|---|---|
| 요청당 추가 입력 | +162 토큰 |
| 답변 오염 | 접사 표기가 영어 답변에 섞여 나옴 |
| 스테머 오탐률 | 약 50% |
| 구조적 문제 | 힌트가 실리는 시점에 본문이 이미 영어 (모델이 원문을 못 봄) |

**근거**: `morphology-ab.md`

---

## 판단에 사용한 데이터 출처 정리

| 주장 | 근거 파일 | 데이터 종류 |
|---|---|---|
| 언어별 토큰 차이 | `benchmark-language-tokens.md` | 토크나이저 확정값 |
| 피벗 출력 절감 | `pivot-quality.md`, `pivot-quality-gemma.md` | API 보고 토큰 수 |
| 절감 원인 분리 | `controlled-experiment.md` | API 보고 토큰 수, 3회 평균 |
| 티어별 총비용 | `tier-remeasure.md` | API 보고 토큰 수 |
| 프롬프트 개선 | `prompt-length-ab.md` | API 보고 토큰 수, 3회 |
| TranslateGemma 검증 | `build/mt-test/gemma-fix-result.txt` | 번역 결과 + 토큰 보존 판정 |
| 형태소 힌트 역효과 | `morphology-ab.md` | API 보고 토큰 수, 답변 전문 |
| 답변 품질 비교 | `pivot-quality.md` | 답변 전문 (사람이 읽고 판단) |

모든 파일은 `build/measurement/` 또는 `build/mt-test/`에 있다. `build/`는 gitignore 대상이라 로컬에만 남는다. 재현하려면 테스트를 다시 돌리면 된다.

---

## 부록 A. 피벗 품질 비교 원본 (베트남어)

같은 질문을 두 경로로 보내고 답변을 나란히 놓았다. 이걸 보고 판단한 것이다.

### 입력 원문 (베트남어)

```
Nhóm của chúng tôi đang chuẩn bị triển khai Nền tảng Sigasig cho ba chi nhánh
trong quý này và cần một kế hoạch di chuyển rõ ràng. Vui lòng giải thích chúng
tôi nên sắp xếp thứ tự thay đổi lược đồ cơ sở dữ liệu, triển khai tiến trình
nền và chuyển đổi phía máy khách như thế nào để giữ thời gian ngừng hoạt động
dưới mười lăm phút trong khung bảo trì cuối tuần này.
```

### 경로 A · 원어 직행 (입력 104토큰 · 출력 1108토큰)

LLM이 베트남어로 답한 원본. **출력 토큰이 1108**이다.

```
Để giảm thiểu thời gian ngừng hoạt động xuống dưới 15 phút trong khoảng thời
gian bảo trì khi triển khai nền tảng Sigasig trên ba chi nhánh, bạn cần một
kế hoạch di chuyển chi tiết, tuần tự và có kiểm soát chặt chẽ giữa các bước
thay đổi lược đồ cơ sở dữ liệu (schema migration), triển khai tiến trình nền
(background jobs/processes) và chuyển đổi phía máy khách (client-side migration).
Dưới đây là cách bạn nên sắp xếp thứ tự và lưu ý từng bước:

### 1. Trước khung bảo trì (Chuẩn bị và kiểm thử)
- Chuẩn bị lược đồ cơ sở dữ liệu mới: Viết các script thay đổi lược đồ
  (migrations) theo hướng backward-compatible...
- Triển khai tiến trình nền mới: Chuẩn bị các tiến trình nền mới...
- Chuẩn bị client-side code...
- Kiểm thử toàn bộ quy trình trên môi trường staging...

### 2. Trong khung bảo trì (< 15 phút)
- Bước 1: Đóng dịch vụ / tạm dừng truy cập
- Bước 2: Thay đổi lược đồ cơ sở dữ liệu
- Bước 3: Triển khai tiến trình nền mới
- Bước 4: Triển khai client-side mới
- Bước 5: Mở lại dịch vụ

### 3. Sau khung bảo trì
- Giám sát logs, hiệu năng và phản hồi người dùng...

(이하 부연 반복, 총 1108 토큰)
```

### 경로 B · TranslateGemma 피벗 → LLM → 재번역 (입력 87토큰 · 출력 388토큰)

**피벗 결과 (TranslateGemma vi→en):**
```
Our team is preparing to deploy the Sigasig platform for three branches this
quarter, and we need a clear migration plan. Please explain to us what order we
should prioritize the changes to the database schema, the deployment of the
background processes, and the client-side conversion, in order to keep the
downtime under 15 minutes during this weekend's maintenance window.
```

**LLM 영어 답변 (388토큰):**
```
To minimize downtime below 15 minutes during the maintenance window when
deploying the Sigasig platform across three branches, it is important to
carefully sequence the migration steps...

1. Database Schema Changes (First)
   - Apply backward-compatible schema changes...
   - This should be done first because both old and new versions can work
     with the updated schema if designed carefully.

2. Deploy Background Processes (Second)
   - Deploy and start new or updated background processes based on the new schema.
   - Ensure these processes are backward-compatible...

3. Client-side Conversion (Last)
   - Finally, deploy the client-side changes...
   - This step typically requires the shortest downtime...

Additional tips:
- Use feature flags to enable new features gradually.
- Test migration steps in staging first.
- Prepare rollback plans.
```

**재번역 결과 (TranslateGemma en→vi) · 용어 "Sigasig" 보존: O**
```
Để giảm thiểu thời gian ngừng hoạt động xuống dưới 15 phút trong khoảng thời
gian bảo trì khi triển khai nền tảng Sigasig trên ba chi nhánh, điều quan
trọng là phải sắp xếp các bước di chuyển một cách cẩn thận...

1. Thay đổi Cấu trúc Cơ sở dữ liệu (Đầu tiên)
   - Áp dụng các thay đổi cấu trúc tương thích ngược...

2. Triển khai các quy trình nền (Thứ hai)
   - Triển khai và khởi động các quy trình nền mới...

3. Chuyển đổi phía khách hàng (Cuối cùng)
   - Cuối cùng, triển khai các thay đổi phía khách hàng...
```

### 비교 판단

| 항목 | 경로 A (원어) | 경로 B (피벗) |
|---|---|---|
| 출력 토큰 | **1108** | **388** (-65%) |
| 답변 구조 | 5단계 + 부연 반복 | **3단계 + 깔끔한 요약** |
| 기술 정확도 | backward-compatible ✓ | backward-compatible ✓ |
| 사용자가 읽을 최종 텍스트 | 길고 반복적 | **간결하고 구조적** |
| 불필요한 부연 | Bước 1~5 안에 같은 말 반복 | 없음 |

**결론: 영어 피벗은 답변을 짧고 체계적으로 만들면서 토큰을 65% 줄인다. 두 마리 토끼.**

---

## 부록 B. 통제 실험 전송 본문

각 셀이 실제로 LLM에 보낸 프롬프트다. 변수를 하나씩만 바꿨다는 것을 확인할 수 있다.

### A · 기준선 (변수 없음)

```
[user]
Nhóm của chúng tôi đang chuẩn bị triển khai Nền tảng Sigasig cho ba chi nhánh
trong quý này và cần một kế hoạch di chuyển rõ ràng...
```
→ 출력 868토큰 (3회 평균)

### B · 프롬프트만

```
[system]
Answer in English only.

[user]
Nhóm của chúng tôi đang chuẩn bị triển khai Nền tảng Sigasig cho ba chi nhánh...
```
→ 출력 577토큰 (-33.5%)

### C · 마스킹만

```
[user]
Nhóm của chúng tôi đang chuẩn bị triển khai {TERM_2} cho ba chi nhánh
trong quý này và cần một kế hoạch di chuyển rõ ràng...
```
→ 출력 682토큰 (-21.5%)

### D · 피벗만

```
[user]
Our team is preparing to deploy the Sigasig Platform to three branches this
quarter and needs a clear migration plan. Please explain how we should
prioritize database schema changes, backend deployment, and client-side
migration to keep downtime under fifteen minutes during this weekend's
maintenance window.
```
→ 출력 756토큰 (-12.9%, 편차 152)

### E · 전부 (현 TIER_2)

```
[system]
Answer in English only.
Keep verbatim: {TERM_2}

[user]
Our team is preparing to deploy {TERM_2} to three branches this quarter and
needs a clear migration plan. Please explain how we should prioritize database
schema changes, background deployment processes, and client-side migration to
keep downtime under fifteen minutes during this weekend's maintenance window.
```
→ 출력 422토큰 (-51.3%)

---

## 부록 C. TranslateGemma 토큰 보존 전수 검사

3방향 × 3회 = 9건. 프롬프트 형식: `Translate the following {from} text to {to}. Keep any {TERM_n} placeholder exactly as-is.`

| 방향 | 회차 | 소요 | 토큰보존 | 감지언어 | 판정 |
|---|---:|---:|---|---|---|
| en → vi | 1 | 1.13s | O | Vietnamese | PASS |
| en → vi | 2 | 1.04s | O | Vietnamese | PASS |
| en → vi | 3 | 1.06s | O | Vietnamese | PASS |
| vi → en | 1 | 0.67s | O | English | PASS |
| vi → en | 2 | 0.63s | O | English | PASS |
| vi → en | 3 | 0.64s | O | English | PASS |

6/6 PASS. `{TERM_2}`가 글자 하나 바뀌지 않고 살아남았다.
argostranslate는 같은 자리에서 `("TERM 2")`, `[TERM 2]`로 깨졌다 (3/3 FAIL).
