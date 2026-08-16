# 라우팅 컴포넌트 (STEP 01)

> 스펙: [routing-spec.md](./routing-spec.md) · 전체 흐름: [pipeline-flow.html](./pipeline-flow.html) · 이슈: #14

다국어 요청이 들어오면 **어떤 처리 경로를 탈지 판별**하는 컴포넌트. 오케스트레이터가 요청 수신 직후 호출하고,
반환된 `RoutingResult`로 이후 단계(마스킹 → 피벗 → LLM → 재번역)를 분기시킨다.

이 컴포넌트는 **판단만 하고 텍스트를 변형하지 않는다.** 마스킹·번역·형태소 분석·LLM 호출·비용 환산은 전부 다른 파트 책임이다.

---

## 구성

```
com.borderless.proxy.routing
├── CostRouter.java              # 진입점. 임계치 분기 로직
├── TokenCalculator.java         # JTokkit o200k_base 토큰 수 계산
├── LanguageDetector.java        # 언어 감지 + 신뢰도
├── RoutingTier.java             # SKIP / TIER_1 / TIER_2 / TIER_3
├── config/
│   └── RoutingProperties.java   # 임계치 설정 바인딩
└── dto/
    ├── RoutingResult.java       # 라우터 최종 반환값
    └── LanguageDetection.java   # 언어 감지 결과
```

| 클래스 | 역할 |
|---|---|
| `CostRouter` | 유일한 공개 진입점. `route(String) → RoutingResult` |
| `TokenCalculator` | `countTokens(String) → int`. o200k_base 고정 |
| `LanguageDetector` | `detect(String) → LanguageDetection` |
| `RoutingTier` | 경로 enum. 후속 단계 수행 여부를 플래그로 노출 |

---

## 사용법

`CostRouter`만 주입받으면 된다.

```java
@Service
@RequiredArgsConstructor
public class ProxyOrchestrator {

    private final CostRouter costRouter;

    public String handle(String originalText) {
        RoutingResult routing = costRouter.route(originalText);
        RoutingTier tier = routing.tier();

        // 임계치 미만이면 전처리 없이 바로 LLM
        if (tier == RoutingTier.SKIP) {
            return llmClient.ask(originalText);
        }

        String text = originalText;
        if (tier.requiresMasking())        text = glossaryMasker.mask(text);
        if (tier.requiresMorphologyHint()) text = morphologyClient.injectHints(text);
        if (tier.requiresPivot())          text = translator.toEnglish(text);

        String answer = llmClient.ask(text);

        if (tier.requiresRetranslation())  answer = translator.toSourceLanguage(answer, routing.detectedLanguage());
        if (tier.requiresMasking())        answer = glossaryMasker.unmask(answer);

        return answer;
    }
}
```

`switch (tier)`로 분기하지 말고 **플래그를 쓰는 걸 권장한다.** 나중에 티어가 늘어도 오케스트레이터를 안 고쳐도 된다.

---

## 판별 규칙

```
1. 토큰 수 계산 (o200k_base)
2. 토큰 수 < token-threshold        → SKIP        (언어 감지 자체를 생략)
3. 언어 감지
4. 신뢰도 < confidence-threshold    → TIER_1      (오판으로 보고 영어 취급)
5. 언어 코드로 티어 매핑
      en → TIER_1
      vi → TIER_2
      tl → TIER_3
      그 외 → TIER_1
```

### 티어별 후속 단계

| 티어 | 대상 | `requiresMasking` | `requiresPivot` | `requiresMorphologyHint` | `requiresRetranslation` |
|---|---|:-:|:-:|:-:|:-:|
| `SKIP` | 임계치 미만 | ✗ | ✗ | ✗ | ✗ |
| `TIER_1` | 영어·미지원 언어 | ✓ | ✗ | ✗ | ✗ |
| `TIER_2` | 베트남어 | ✓ | ✓ | ✗ | ✓ |
| `TIER_3` | 필리핀어(타갈로그) | ✓ | ✓ | ✓ | ✓ |

`TIER_1`도 마스킹은 수행한다. 영어 요청에도 팀 용어집 보호는 필요하다고 해석했다.
`requiresRetranslation`은 `requiresPivot`에서 파생된다. 피벗한 경우에만 되돌릴 대상이 생긴다.

---

## 설정

`application.yaml`. 실측으로 조정할 값이라 하드코딩하지 않았다.

```yaml
routing:
  token-threshold: 50        # 이 값 미만이면 SKIP
  confidence-threshold: 0.7  # 이 값 미만이면 TIER_1 폴백
```

값을 생략하면 `RoutingProperties`의 `@DefaultValue`(50 / 0.7)가 적용된다. 범위를 벗어난 값은 애플리케이션 기동 시점에 예외로 막는다.

### 임계치 50의 근거

o200k_base 실측값이다. 짧은 인사말과 실제 업무 문단 사이가 갈리는 지점으로 잡았다.

| 샘플 | 토큰 수 |
|---|---:|
| 짧은 영어 인사말 | 9 |
| 짧은 베트남어 문장 | 10 |
| 긴 영어 문단 | 58 |
| 긴 베트남어 문단 | 86 |
| 긴 필리핀어 문단 | 114 |

같은 의미의 문장으로 비교하면 **영어 10 / 베트남어 14 / 필리핀어 20** 토큰이다.
비영어권 언어의 토큰화 효율이 낮다는 이 컴포넌트의 존재 이유가 수치로 확인된다.

---

## DB 기록

`ProxyRequest` 엔티티의 컬럼과 이렇게 대응된다.

| `ProxyRequest` 컬럼 | 라우팅 값 | 비고 |
|---|---|---|
| `sourceLang` (length 10) | `routing.detectedLanguage()` | `"en"`, `"vi"`, `"tl"`, `"und"` |
| `isPivoted` | `routing.tier().requiresPivot()` | |
| `routeDecision` (length 20) | `routing.tier().name()` | `"SKIP"`, `"TIER_1"`, `"TIER_2"`, `"TIER_3"` |

`estimatedTokens`는 대시보드 토큰/비용 집계에 그대로 쓸 수 있다.
단, **실제 비용(원화/달러) 환산은 라우팅 스코프 밖**이다. 여기선 토큰 수만 반환한다.

---

## 확장

### 지원 언어 추가

1. `RoutingTier.TIER_BY_LANGUAGE`에 매핑 추가
2. 언어 감지 라이브러리가 그 코드를 지원하는지 확인 (내장 프로파일 70개)
3. `RoutingSamples`에 해당 언어 샘플 문단 추가 (임계치를 넘는 길이로)
4. `CostRouterTest.SpecScenarios.specCases()`에 케이스 추가

### 티어 추가

`RoutingTier`에 상수를 추가하고 4개 플래그를 지정한다.
플래그 조합으로 표현이 안 되는 새 단계가 필요하면 플래그를 하나 더 늘리는 쪽이,
오케스트레이터에 `switch`를 만드는 쪽보다 낫다.

---

## 테스트

```powershell
$env:JAVA_HOME="<JDK 21 경로>"
./gradlew test --tests "com.borderless.proxy.routing.*"
```

| 테스트 | 범위 |
|---|---|
| `TokenCalculatorTest` | 실측 토큰 수 고정, 공백 처리, 언어별 토큰 효율 |
| `LanguageDetectorTest` | en/vi/tl 감지, 감지 불가 시 폴백 |
| `CostRouterTest` | 스펙 케이스 표 전부 + 두 임계치의 경계값 |
| `RoutingPropertiesTest` | 설정 바인딩, 기본값, 범위 검증 |
| `RoutingSmokeTest` | 실제 빈 조립 + `application.yaml` 임계치로 전체 흐름 |

`RoutingSamples`에 샘플 텍스트가 모여 있다. 다른 파트에서도 다국어 테스트에 재사용할 수 있다.

> 참고: `RoutingSmokeTest`는 DB에 의존하지 않도록 전체 컨텍스트 대신 라우팅 빈만 올린다.
> `@SpringBootTest`는 MySQL이 필요해서 로컬에서 뜨지 않는다.

---

## 외부 자료

| 자료 | 용도 |
|---|---|
| [JTokkit](https://github.com/knuddelsgmbh/jtokkit) · [문서](https://jtokkit.knuddels.de/) | 토큰 계산. OpenAI `tiktoken`의 Java 구현 |
| [optimaize/language-detector](https://github.com/optimaize/language-detector) | 언어 감지. 70개 언어 내장 프로파일 |
| [openai/tiktoken](https://github.com/openai/tiktoken) | 인코딩별 모델 대응표 확인용 |
| [ISO 639 코드 목록](https://www.loc.gov/standards/iso639-2/php/code_list.php) | 언어 코드, `und` 정의 |
| [Spring Boot 외부 설정](https://docs.spring.io/spring-boot/reference/features/external-config.html) | `@ConfigurationProperties` 레코드 바인딩 |

### 인코딩 주의

`EncodingType.O200K_BASE`를 **반드시 명시**해야 한다. 기본값이나 `CL100K_BASE`를 쓰면
실제 호출 모델과 토큰 수가 어긋나 임계치 판단이 틀어진다. `o200k_base`는 gpt-4o 계열이 쓰는 인코딩이고,
JTokkit은 1.1.0부터 지원한다.
