# 문서

Borderless AI Proxy 백엔드 설계·구현 문서.

| 문서 | 내용 |
|---|---|
| [pipeline-flow.html](./pipeline-flow.html) | 전체 파이프라인 흐름도. 요청이 거치는 5단계 + 언어 티어 분기 |
| [routing-spec.md](./routing-spec.md) | 라우팅 컴포넌트 스펙 (요구사항 원본) |
| [routing.md](./routing.md) | 라우팅 컴포넌트 구현 문서. 사용법·설정·확장·외부 자료 |
| [conventions.md](./conventions.md) | 코드 컨벤션. DTO·enum·설정·테스트 규칙 |

프로젝트 기여 방법과 깃 컨벤션은 루트의 [CONTRIBUTING.md](../CONTRIBUTING.md)에 있다.

---

## 파이프라인 구현 현황

| 단계 | 담당 컴포넌트 | 상태 | 이슈 |
|---|---|---|---|
| STEP 00 · 요청 수신 | `ProxyController` | 미구현 | #9 |
| STEP 01 · 언어 감지 & 티어 분류 | `routing` | **구현 완료** → [문서](./routing.md) | #14 |
| STEP 02 · 용어집 마스킹 | `glossary` | 엔티티·리포지토리만 | #7, #10 |
| STEP 03 · 영어 피벗 (+ 형태소 힌트) | 번역 클라이언트 / FastAPI | 미구현 | |
| STEP 04 · LLM 추론 | LLM 클라이언트 | 미구현 | |
| STEP 05 · 원어 재번역 + 마스킹 복원 | 번역 클라이언트 | 미구현 | |
| STEP 06 · 응답 + 대시보드 로깅 | `UsageLog` | 엔티티만 | #7 |
| 전체 조립 | `ProxyOrchestrator` | 미구현 | #9 |

### 공통 기반

| 항목 | 상태 | 이슈 |
|---|---|---|
| 전역 예외 처리 (`GlobalExceptionHandler`, `ErrorResponse`) | 완료 | #12 |
| 비동기 설정 (`AsyncConfig`) | 완료 | #12 |
| 도메인 엔티티 (`Member`, `Team`, `Glossary`, `ProxyRequest`, `UsageLog`) | 완료 | #7 |
| 데이터 접근 계층 | 일부 (`glossary`만) | #10 |
| CI/CD (GitHub Actions, Docker) | 완료 | #3, #5 |
| FastAPI 사이드카 | `/health` 스켈레톤 | |

---

## 기술 스택

- Java 21, Spring Boot 4.1.0, Gradle
- Spring Data JPA + MySQL
- WebFlux / WebMVC
- 라우팅: [JTokkit](https://github.com/knuddelsgmbh/jtokkit) (토큰 계산), [optimaize/language-detector](https://github.com/optimaize/language-detector) (언어 감지)
- 사이드카: FastAPI (형태소 분석용 예정)

## 로컬 실행

```powershell
# 테스트 (라우팅만)
./gradlew test --tests "com.borderless.proxy.routing.*"

# 전체 빌드
./gradlew build
```

> `ProxyApplicationTests.contextLoads()`는 MySQL이 떠 있어야 통과한다.
> DB 없이 검증하려면 위처럼 패키지를 지정해서 돌리면 된다.
