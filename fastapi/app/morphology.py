"""타갈로그 형태소 힌트 생성 (파이프라인 STEP 03-B).

타갈로그는 접사(mag-, nag-, -in-, -an 등)와 음절 반복으로 의미를 바꾼다.
번역기와 LLM이 이 구조를 놓치면 시제·상(aspect)이 뭉개지므로, 어근과 접사를
분해해 힌트로 함께 넘긴다.

이 모듈은 텍스트를 변형하지 않는다. 원문은 그대로 두고 힌트만 만들어 돌려준다.
치환 여부는 호출부(오케스트레이터)가 결정한다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from tglstemmer import stemmer

# 용어집 마스킹 토큰. 이 패턴은 형태소 분석에서 제외한다.
#
# GlossaryService.generateMaskedToken()이 만드는 실제 형식은
# "{TERM_" + UUID 앞 12자리 대문자 + "}" 이다. 예: {TERM_3F9A2B7C1D0E}
# 문서와 주석에는 {TERM_01}로 적혀 있지만 실제 값에는 알파벳이 섞인다.
# 숫자만 매칭하면 실제 토큰이 걸러지지 않아 TERM, F, A 같은 조각으로 쪼개진다.
# 영숫자를 모두 허용해 생성 규칙이 바뀌어도 버티게 한다.
#
# 제외하는 이유: tglstemmer.get_stem("{TERM_01}")은 "{term_01}"을 돌려준다.
# 소문자로 바뀐 값이 힌트에 실리면 프롬프트가 오염되고, 모델이 그 형태를 따라 쓰면
# TermRestorer가 사전 키를 찾지 못해 복원이 깨진다.
MASKED_TOKEN = re.compile(r"\{TERM_[0-9A-Za-z]+\}")

# 단어 추출 패턴. 마스킹 토큰을 하나의 덩어리로 먼저 잡아 쪼개지지 않게 한다.
# 첫 번째 분기가 MASKED_TOKEN과 같은 형태여야 토큰이 통째로 잡힌다.
#
# nltk 기반 word_tokenize를 쓰지 않는 이유: punkt_tab 코퍼스를 런타임에 내려받아야 해서
# 컨테이너에 네트워크와 쓰기 가능한 경로가 필요해진다. get_stem은 단어 단위로 동작하므로
# 토큰화를 직접 하면 코퍼스 없이 완결된다.
WORD = re.compile(r"\{TERM_[0-9A-Za-z]+\}|[^\W\d_]+(?:[-'’][^\W\d_]+)*", re.UNICODE)


@dataclass(frozen=True)
class TokenHint:
    """단어 하나의 형태소 분해 결과."""

    token: str
    stem: str
    prefix: str | None = None
    infix: str | None = None
    suffix: str | None = None
    reduplication: str | None = None

    def affixes(self) -> list[str]:
        """붙어 있던 접사 목록. 힌트 문장을 만들 때 쓴다."""
        return [a for a in (self.prefix, self.infix, self.suffix, self.reduplication) if a]

    def describe(self) -> str:
        """사람이 읽는 한 줄 표기. 예: nagsulat = nag + sulat"""
        return f"{self.token} = {' + '.join(self.affixes())} + {self.stem}"


@dataclass(frozen=True)
class MorphologyResult:
    """요청 텍스트 하나에 대한 분석 결과."""

    text: str
    hint: str
    tokens: list[TokenHint] = field(default_factory=list)


def _to_hint(token: str) -> TokenHint | None:
    """단어 하나를 분해한다. 접사가 없으면 힌트로서 값이 없으므로 None."""
    stem = stemmer.get_stem(token)

    hint = TokenHint(
        token=token,
        stem=str(stem),
        prefix=stem.pre,
        infix=getattr(stem, "inf", None),
        suffix=stem.suf,
        reduplication=getattr(stem, "rep", None),
    )

    # 접사가 없으면 어근 = 원형이다. 이런 단어까지 힌트에 넣으면 프롬프트만 길어지고
    # 토큰 비용이 늘어난다. 절감이 목적인 서비스에서 역효과다.
    if not hint.affixes():
        return None

    return hint


def analyze(text: str) -> MorphologyResult:
    """텍스트에서 접사가 붙은 단어를 찾아 형태소 힌트를 만든다.

    빈 문자열이나 접사가 하나도 없는 텍스트는 빈 힌트를 돌려준다.
    호출부는 hint가 비었으면 프롬프트에 아무것도 붙이지 않으면 된다.
    """
    if not text or not text.strip():
        return MorphologyResult(text=text or "", hint="", tokens=[])

    hints: list[TokenHint] = []
    seen: set[str] = set()

    for token in WORD.findall(text):
        if MASKED_TOKEN.fullmatch(token):
            continue
        # 같은 단어가 반복되면 힌트도 중복된다. 첫 번째만 남긴다.
        if token in seen:
            continue
        seen.add(token)

        hint = _to_hint(token)
        if hint is not None:
            hints.append(hint)

    return MorphologyResult(text=text, hint=_build_hint(hints), tokens=hints)


def _build_hint(hints: list[TokenHint]) -> str:
    """LLM 프롬프트에 그대로 붙일 수 있는 한 줄 힌트를 만든다."""
    if not hints:
        return ""

    breakdown = "; ".join(h.describe() for h in hints)
    return (
        "Tagalog morphology hints (root + affixes), "
        f"use them to preserve tense and aspect: {breakdown}"
    )
