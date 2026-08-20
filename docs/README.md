# 문서

Borderless AI Proxy 백엔드 설계·구현 문서.

| 문서 | 내용 |
|---|---|
| [architecture.html](./architecture.html) | 시스템 아키텍처. 배포 토폴로지·패키지 구성·요청 파이프라인·티어 매트릭스·데이터 모델·알려진 공백 |
| [demo-storyboard.html](./demo-storyboard.html) | 데모 영상 스토리보드. 6개 씬 순차 재생 + 촬영 노트 |
| [team-flow.html](./team-flow.html) | 팀 단위 사용 플로우. 프록시 개념·팀원 예시·데모 촬영 컷 |
| [pipeline-flow.html](./pipeline-flow.html) | 전체 파이프라인 흐름도. 요청이 거치는 5단계 + 언어 티어 분기 |
| [dashboard-mockup.html](./dashboard-mockup.html) | 사용량 대시보드 목업. 지표 구성·스타일 제안 (더미 데이터) |
| [routing-spec.md](./routing-spec.md) | 라우팅 컴포넌트 스펙 (요구사항 원본) |
| [routing.md](./routing.md) | 라우팅 컴포넌트 구현 문서. 사용법·설정·확장·외부 자료 |
| [pipeline-measurement.md](./pipeline-measurement.md) | 파이프라인 실측 보고서. 티어별 절감 수치·형태소 힌트 A/B·발견된 문제 |
| [conventions.md](./conventions.md) | 코드 컨벤션. DTO·enum·설정·테스트 규칙 |

프로젝트 기여 방법과 깃 컨벤션은 루트의 [CONTRIBUTING.md](../CONTRIBUTING.md)에 있다.

---

## 파이프라인 구현 현황

| 단계 | 담당 컴포넌트 | 상태 | 이슈 |
|---|---|---|---|
| STEP 00 · 요청 수신 | `ProxyController` | 완료 | #9 |
| STEP 01 · 언어 감지 & 티어 분류 | `routing` | 완료 → [문서](./routing.md) | #14 |
| STEP 02 · 용어집 마스킹 | `glossary` | 완료 | #7, #10, #15 |
| STEP 03 · 영어 피벗 | `DeepLTranslationClient` | 완료 | #29 |
| STEP 03-B · 형태소 힌트 | FastAPI 사이드카 | 배선 완료 · **기본 비활성화** | #49 |
| STEP 04 · LLM 추론 (+ 시스템 프롬프트) | `OpenAiLlmClient`, `SystemPromptBuilder` | 완료 | #29, #50 |
| STEP 05 · 원어 재번역 + 마스킹 복원 | `DeepLTranslationClient`, `TermRestorer` | 완료 | #29, #15 |
| STEP 06 · 응답 + 대시보드 로깅 | `UsageRecorder`, `UsageLog` | 완료 | #42, #57 |
| 전체 조립 | `ProxyOrchestrator` | 완료 (테스트 30개) | #9, #51 |

STEP 03-B는 실측에서 역효과가 확인돼 `app.morphology.inject-hint: false`로 꺼져 있다.
사이드카·클라이언트는 그대로 남아 있고 플래그만 켜면 다시 붙는다.
수치와 근거는 [실측 보고서](./pipeline-measurement.md#형태소-힌트-ab-49)에 있다.

### 공통 기반

| 항목 | 상태 | 이슈 |
|---|---|---|
| 전역 예외 처리 (`GlobalExceptionHandler`, `ErrorResponse`) | 완료 | #12 |
| 비동기 설정 (`AsyncConfig`) | 완료 | #12 |
| 도메인 엔티티 (`Member`, `Team`, `Glossary`, `ProxyRequest`, `UsageLog`) | 완료 | #7 |
| 데이터 접근 계층 | 완료 (`glossary`, `member`, `proxy`, `billing`) | #10 |
| CI/CD (GitHub Actions, Docker) | 완료 | #3, #5 |
| 카카오 소셜 로그인 | 완료 | #32 |
| 토큰 비용 환산·절감 수수료 | 완료 (DeepL 비용 미차감) | #42 |
| FastAPI 사이드카 | `/health` + `/morphology/hints` | #49 |

---

## 기술 스택

- Java 21, Spring Boot 4.1.0, Gradle
- Spring Data JPA + MySQL
- WebFlux / WebMVC
- 라우팅: [JTokkit](https://github.com/knuddelsgmbh/jtokkit) (토큰 계산), [optimaize/language-detector](https://github.com/optimaize/language-detector) (언어 감지)
- 외부 API: OpenAI Chat Completions, DeepL
- 사이드카: FastAPI + [tglstemmer](https://pypi.org/project/tglstemmer/) (타갈로그 형태소)

## 로컬 실행

```powershell
# 단위 테스트 (DB 불필요)
./gradlew test --tests "com.borderless.proxy.routing.*" --tests "com.borderless.proxy.service.*"

# 전체 빌드
./gradlew build
```

> `ProxyApplicationTests.contextLoads()`는 `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` /
> `KAKAO_CLIENT_ID`가 있어야 통과한다. DB 없이 검증하려면 위처럼 패키지를 지정해서 돌리면 된다.
> CI는 `./gradlew build -x test`로 테스트를 건너뛰므로 이 실패가 파이프라인에 드러나지 않는다.

### 형태소 사이드카

```powershell
cd fastapi
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

> 사이드카에는 인증이 없다. 같은 호스트의 컨테이너로만 접근한다는 전제이므로
> 로컬에서도 `127.0.0.1`에 바인딩하고, 외부에 노출하려면 인증을 먼저 붙여야 한다.
