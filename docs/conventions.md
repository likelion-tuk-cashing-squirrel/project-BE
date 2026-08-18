# 코드 컨벤션

라우팅 파트(#14)를 구현하면서 정한 규칙을 정리했다.
**팀 합의를 거친 규칙이 아니라 제안이다.** 이견 있으면 PR이나 이슈에서 얘기하고 고치자.

---

## DTO

### 1. record를 쓴다

읽기 전용 데이터 전달에는 클래스 대신 `record`를 쓴다. `@Getter`, `equals`, `hashCode`, `toString`이 공짜로 붙고
불변이 보장돼서 파이프라인 단계 사이로 넘겨도 중간에 바뀔 걱정이 없다.

```java
public record RoutingResult(
        RoutingTier tier,
        String detectedLanguage,
        int estimatedTokens,
        double confidence
) { }
```

Lombok `@Builder`가 필요할 만큼 필드가 많아지면(대략 6개 이상) 그때 클래스로 바꾼다.

### 2. 컴팩트 생성자에서 검증한다

DTO가 스스로 불변식을 지킨다. 잘못된 값이 파이프라인 안쪽까지 흘러가서 엉뚱한 곳에서 터지는 걸 막는다.

```java
public RoutingResult {
    if (tier == null) {
        throw new IllegalArgumentException("tier는 null일 수 없습니다.");
    }
    if (confidence < 0.0 || confidence > 1.0) {
        throw new IllegalArgumentException("confidence는 0.0 ~ 1.0 범위여야 합니다: " + confidence);
    }
}
```

- 예외 메시지는 **한국어로, 잘못된 값을 함께 적는다.** 로그만 보고 원인을 알 수 있어야 한다
- 컨트롤러 계층 요청 DTO는 여기서 검증하지 말고 Bean Validation(`@NotNull`, `@Size`)을 쓴다.
  컴팩트 생성자 검증은 내부 계층 DTO용이다

### 3. null 대신 의미 있는 기본값

DTO 필드로 `null`을 흘리지 않는다. 호출부마다 널 체크가 번지고, 빼먹으면 NPE로 죽는다.

```java
// 값이 없음을 표현하는 명시적 상수
public static final String UNDETERMINED_LANGUAGE = "und";

public RoutingResult {
    if (detectedLanguage == null || detectedLanguage.isBlank()) {
        detectedLanguage = UNDETERMINED_LANGUAGE;   // 정규화
    }
}
```

`"und"`는 ISO 639-2의 undetermined 코드다. 임의 문자열보다 표준 코드를 쓰는 편이 DB에 그대로 저장해도 안전하다.

값이 없는 상태를 판별할 일이 많으면 질의 메서드를 같이 준다.

```java
public boolean isUndetermined() {
    return UNDETERMINED_LANGUAGE.equals(languageCode);
}
```

### 4. 자주 만드는 조합은 정적 팩토리로

생성자 인자를 늘어놓는 대신 의도가 드러나는 이름을 붙인다.

```java
RoutingResult.skip(tokens)          // 임계치 미만 통과
LanguageDetection.undetermined()    // 언어 특정 실패
```

인자 없는 고정값(`undetermined()`)은 상수 인스턴스를 재사용한다.

---

## enum

### boolean 대신 enum, enum에 동작 정보를 담는다

분기가 3개 이상이면 boolean 조합을 쓰지 않는다. `isPivot` 하나로는 "형태소 분석이 필요한 언어"를 표현할 수 없다.

호출부에 `switch`를 만들지 말고 **enum이 자기 특성을 알려주게** 한다.

```java
public enum RoutingTier {
    SKIP(false, false, false),
    TIER_1(true, false, false),
    TIER_2(true, true, false),
    TIER_3(true, true, true);

    private final boolean requiresMasking;
    private final boolean requiresPivot;
    private final boolean requiresMorphologyHint;

    public boolean requiresMasking() { return requiresMasking; }
}
```

이러면 티어가 늘어도 enum만 고치면 되고, 호출부 `switch`를 빠짐없이 찾아 고치는 일이 없다.

### 매핑 테이블과 폴백은 enum 안에

외부 값(언어 코드 등) → enum 변환은 enum의 정적 메서드로 둔다. 폴백 대상도 상수로 노출해서 의도를 드러낸다.

```java
private static final Map<String, RoutingTier> TIER_BY_LANGUAGE = Map.of(
        "en", TIER_1, "vi", TIER_2, "tl", TIER_3
);

public static final RoutingTier FALLBACK = TIER_1;

public static RoutingTier fromLanguageCode(String languageCode) {
    if (languageCode == null || languageCode.isBlank()) {
        return FALLBACK;
    }
    return TIER_BY_LANGUAGE.getOrDefault(languageCode.toLowerCase(Locale.ROOT), FALLBACK);
}
```

외부 입력은 `toLowerCase(Locale.ROOT)`로 정규화한다. `Locale`을 안 주면 터키어 로케일에서 `I`가 깨진다.

---

## 패키지 구조

기능(도메인)별로 먼저 나누고, 그 안에서 계층별로 나눈다.

```
com.borderless.proxy
├── global/              # 전역 관심사
│   ├── config/
│   └── exception/
├── routing/             # 기능 단위
│   ├── CostRouter.java  # 진입점은 패키지 루트에
│   ├── config/          # 그 기능 전용 설정
│   └── dto/
├── glossary/
├── member/
└── proxy/
```

- 진입점 클래스는 패키지 루트, 부속 타입은 하위 패키지
- 기능 전용 설정은 `global/config`가 아니라 `그기능/config`에 둔다
- JPA 엔티티는 `entity`, 내부 데이터 전달 객체는 `dto`

---

## 설정값

### 하드코딩하지 않고 record로 바인딩한다

튜닝 대상 값(임계치, 타임아웃, 재시도 횟수)은 코드에 박지 않는다.

```java
@ConfigurationProperties(prefix = "routing")
public record RoutingProperties(
        @DefaultValue("50") int tokenThreshold,
        @DefaultValue("0.7") double confidenceThreshold
) {
    public RoutingProperties {
        if (tokenThreshold < 0) {
            throw new IllegalArgumentException("routing.token-threshold는 음수일 수 없습니다: " + tokenThreshold);
        }
    }
}
```

- `@Value` 대신 `@ConfigurationProperties`. 관련 값이 한 타입에 모이고 검증할 곳이 생긴다
- `@DefaultValue`로 기본값을 준다. 설정 파일이 없어도 뜬다
- **예외 메시지에는 yaml 키 이름(`routing.token-threshold`)을 쓴다.** 자바 필드명(`tokenThreshold`)을 적으면
  설정 파일에서 어딜 고쳐야 할지 모른다
- 레코드 기반 바인딩은 `ProxyApplication`의 `@ConfigurationPropertiesScan`으로 등록된다.
  새로 만들면 별도 등록 없이 잡힌다
- yaml 키는 케밥 케이스(`token-threshold`)

---

## 빈

- 필드 주입(`@Autowired`) 대신 생성자 주입. Lombok `@RequiredArgsConstructor` + `private final`
- 초기화 비용이 큰 객체(인코딩 테이블, 언어 프로파일)는 싱글턴 빈 필드로 한 번만 만들어 재사용한다
- 테스트에서 스텁을 넣어야 하면 패키지 프라이빗 생성자를 하나 더 둔다.
  스프링은 `@Autowired`가 없고 기본 생성자가 있으면 그걸 고른다

---

## 로깅

- Lombok `@Slf4j`
- 분기 판단 결과는 `debug`로 남긴다. 왜 그 경로로 갔는지 재현할 수 있어야 한다
- 파라미터는 문자열 연결(`+`) 대신 플레이스홀더(`{}`)
- **원문 텍스트 전체를 로그에 찍지 않는다.** 길이나 토큰 수 같은 메타데이터만 남긴다

```java
log.debug("토큰 수 {}가 임계치 {} 미만이라 SKIP합니다.", estimatedTokens, threshold);
```

---

## 주석

- **왜(why)를 적는다.** 무엇을(what) 하는지는 코드가 말한다
- 판단이 갈릴 수 있는 선택에는 근거를 남긴다

```java
/**
 * {@link String#isBlank()}은 {@code Character.isWhitespace}를 쓰기 때문에
 * non-breaking space(U+00A0)처럼 화면상 공백인 문자를 걸러내지 못한다.
 * 붙여넣기로 들어오는 텍스트에 섞이기 쉬운 문자라 {@code isSpaceChar}까지 함께 본다.
 */
```

- 공개 API에는 Javadoc. `@param`으로 null·경계값 동작을 명시한다
- **없는 클래스를 `{@link}`로 참조하면 `./gradlew javadoc`이 실패한다.**
  아직 안 만든 클래스는 일반 문장으로 쓴다

---

## 테스트

- 프로덕션과 같은 패키지에 둔다
- `@DisplayName`은 한국어로, 단정문으로 쓴다. 리포트가 그대로 명세가 된다
- 관련 케이스는 `@Nested`로 묶는다
- 같은 로직에 값만 다른 케이스는 `@ParameterizedTest`
- 공유 샘플 데이터는 픽스처 클래스로 분리한다 (`RoutingSamples`)
- 실측값을 단정할 때는 **그 값이 왜 그런지 주석으로 남긴다.** 라이브러리 버전이 바뀌어 깨졌을 때 판단 근거가 된다
- 경계값은 양쪽을 다 본다. 임계치 50이면 49와 50을 함께 검증한다
- 외부 라이브러리를 감싼 클래스는 실제 라이브러리로 테스트한다.
  분기 로직만 볼 때는 목으로 경계값을 정확히 만든다
- 엣지 케이스는 기본으로 넣는다: `null`, 빈 문자열, 공백만, 아주 긴 입력

---

## 커밋

`CONTRIBUTING.md`를 따른다. 라우팅 파트에서 적용한 방식은 이렇다.

- **기능 단위로 쪼갠다.** 프로덕션 코드와 그 테스트는 별도 커밋으로 나눴다
- **각 커밋이 그 자체로 빌드가 깨지지 않아야 한다.**
  의존성을 추가해야 컴파일되는 코드라면 `build:` 커밋을 앞에 둔다
- 설정을 읽는 코드보다 설정 추가 커밋이 먼저 온다
- 커밋 본문에 무엇을 왜 그렇게 했는지 적는다

```
feat: CostRouter 통합 및 임계치 분기 로직

- 토큰 수 계산 → 임계치 미만이면 언어 감지 없이 SKIP
- 신뢰도가 임계치 미만이면 TIER_1(영어 취급)로 폴백
```
