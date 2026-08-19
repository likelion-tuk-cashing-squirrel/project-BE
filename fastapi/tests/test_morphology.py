"""형태소 힌트 로직 단위 테스트.

nltk 코퍼스 없이 동작해야 한다. NLTK_DATA를 없는 경로로 강제해 그걸 고정한다.
컨테이너에는 punkt_tab 같은 코퍼스가 없으므로, 여기서 통과해야 배포 환경에서도 돈다.
"""

import os

os.environ["NLTK_DATA"] = os.path.join(os.path.dirname(__file__), "no-such-nltk-data")

import pytest

from app import morphology


class TestAffixBreakdown:
    def test_prefix_is_separated(self):
        result = morphology.analyze("nagsulat")

        assert len(result.tokens) == 1
        token = result.tokens[0]
        assert token.token == "nagsulat"
        assert token.stem == "sulat"
        assert token.prefix == "nag"

    def test_infix_is_separated(self):
        result = morphology.analyze("binasa")

        assert result.tokens[0].stem == "basa"
        assert result.tokens[0].infix == "in"

    def test_suffix_is_separated(self):
        result = morphology.analyze("punitin")

        assert result.tokens[0].stem == "punit"
        assert result.tokens[0].suffix == "in"

    def test_hint_contains_root_and_affix(self):
        result = morphology.analyze("nagsulat")

        assert "nagsulat = nag + sulat" in result.hint

    def test_original_text_is_not_modified(self):
        # 이 서비스는 판단만 한다. 텍스트 치환은 호출부 책임이다.
        text = "Paano ko iu-update ang release"

        assert morphology.analyze(text).text == text


class TestMaskedTokens:
    """GlossaryService가 만드는 실제 형식으로 검증한다.

    실제 토큰은 "{TERM_" + UUID 앞 12자리 대문자 + "}" 이다. 예: {TERM_3F9A2B7C1D0E}
    문서와 주석에 적힌 {TERM_01}만 보고 숫자 패턴으로 걸러내면 실제 값이 통과해버린다.
    """

    REAL_A = "{TERM_3F9A2B7C1D0E}"
    REAL_B = "{TERM_88AA11BB22CC}"

    def test_real_format_token_is_skipped(self):
        result = morphology.analyze(f"nagsulat ang {self.REAL_A}")

        analyzed = [t.token for t in result.tokens]
        assert analyzed == ["nagsulat"]

    # 16진수 알파벳(A-F) 조각 중 tglstemmer가 접사로 오판하는 조합이 있다.
    # 예: AABA -> aba + 접사 'a', BABABA -> baba + 접사 'ba'
    # 토큰을 통째로 걸러내지 않으면 이런 조각이 힌트에 실려 프롬프트를 오염시킨다.
    POLLUTING = "{TERM_AABA12BABABA}"

    def test_hex_fragments_do_not_pollute_hint(self):
        result = morphology.analyze(f"{self.POLLUTING} nagsulat")

        analyzed = [t.token for t in result.tokens]
        assert analyzed == ["nagsulat"]
        assert "aba" not in result.hint
        assert "baba" not in result.hint

    def test_token_containing_only_polluting_fragments_yields_no_hint(self):
        result = morphology.analyze(self.POLLUTING)

        assert result.hint == ""
        assert result.tokens == []

    def test_real_format_token_never_appears_in_hint(self):
        result = morphology.analyze(f"nagsulat ang {self.REAL_A} at {self.REAL_B}")

        assert "TERM" not in result.hint
        assert "term" not in result.hint

    @pytest.mark.parametrize("token", ["{TERM_01}", "{TERM_3F9A2B7C1D0E}", "{TERM_abc123}"])
    def test_various_token_shapes_are_skipped(self, token):
        result = morphology.analyze(f"nagsulat {token}")

        assert [t.token for t in result.tokens] == ["nagsulat"]

    def test_masked_token_only_text_produces_no_hint(self):
        result = morphology.analyze(f"{self.REAL_A} {self.REAL_B}")

        assert result.hint == ""
        assert result.tokens == []

    def test_text_is_returned_unchanged_with_real_tokens(self):
        # 복원이 깨지지 않는 근거. 이 서비스는 텍스트를 건드리지 않는다.
        text = f"nagsulat ang {self.REAL_A}"

        assert morphology.analyze(text).text == text


class TestNoiseReduction:
    def test_words_without_affixes_are_omitted(self):
        # 어근이 곧 원형인 단어를 힌트에 넣으면 프롬프트만 길어진다.
        # 토큰 절감이 목적인 서비스에서 역효과다.
        result = morphology.analyze("ko ang ng")

        assert result.tokens == []
        assert result.hint == ""

    def test_duplicate_words_appear_once(self):
        result = morphology.analyze("nagsulat at nagsulat")

        assert len(result.tokens) == 1

    @pytest.mark.parametrize("text", ["", "   ", "\n"])
    def test_blank_text_produces_empty_hint(self, text):
        result = morphology.analyze(text)

        assert result.hint == ""
        assert result.tokens == []

    def test_digits_and_symbols_do_not_break_analysis(self):
        result = morphology.analyze("SOP1 2026 !!! ###")

        assert result.hint == ""


class TestSentence:
    def test_multiple_affixed_words_are_all_reported(self):
        # 문장 단위로도 nltk word_tokenize 없이 동작해야 한다.
        result = morphology.analyze("Paano ko iu-update ang {TERM_01} pagkatapos ng release")

        stems = {t.token: t.stem for t in result.tokens}
        assert stems["Paano"] == "ano"
        assert stems["pagkatapos"] == "tapos"
        assert result.hint.startswith("Tagalog morphology hints")
