from fastapi import FastAPI
from pydantic import BaseModel, Field

from app import morphology

app = FastAPI(title="borderless-fastapi")


class MorphologyHintsRequest(BaseModel):
    """형태소 힌트 요청.

    text는 마스킹이 끝난 상태로 들어온다. {TERM_01} 같은 치환 토큰은 분석에서 제외된다.
    """

    text: str = Field(..., description="분석할 타갈로그 텍스트 (마스킹 적용 후)")


class TokenHintResponse(BaseModel):
    token: str
    stem: str
    prefix: str | None = None
    infix: str | None = None
    suffix: str | None = None
    reduplication: str | None = None


class MorphologyHintsResponse(BaseModel):
    """분석 결과.

    hint는 LLM 프롬프트에 그대로 붙일 수 있는 문장이다. 접사가 붙은 단어가 없으면 빈 문자열이다.
    tokens는 단어별 분해 내역으로, 디버깅과 데모 시연용이다.
    """

    text: str
    hint: str
    tokens: list[TokenHintResponse]


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/morphology/hints", response_model=MorphologyHintsResponse)
def morphology_hints(request: MorphologyHintsRequest) -> MorphologyHintsResponse:
    """타갈로그 텍스트의 어근과 접사를 분해해 힌트를 돌려준다 (STEP 03-B).

    텍스트를 변형하지 않는다. 원문은 그대로 두고 힌트만 만든다.
    프롬프트에 어떻게 끼워넣을지는 호출부가 결정한다.
    """
    result = morphology.analyze(request.text)

    return MorphologyHintsResponse(
        text=result.text,
        hint=result.hint,
        tokens=[TokenHintResponse(**vars(t)) for t in result.tokens],
    )
