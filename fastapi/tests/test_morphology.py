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
    def test_masked_token_is_skipped(self):
        # get_stem("{TERM_01}")은 "{term_01}"을 돌려준다. 소문자로 바뀌면
        # TermRestorer가 사전 키를 못 찾아 복원이 깨진다.
        result = morphology.analyze("nagsulat ang {TERM_01}")

        analyzed = [t.token for t in result.tokens]
        assert "{TERM_01}" not in analyzed
        assert "TERM_01" not in analyzed
        assert "nagsulat" in analyzed

    def test_masked_token_never_appears_in_hint(self):
        result = morphology.analyze("nagsulat ang {TERM_01} at {TERM_02}")

        assert "TERM_01" not in result.hint
        assert "term_01" not in result.hint

    def test_masked_token_only_text_produces_no_hint(self):
        result = morphology.analyze("{TERM_01} {TERM_02}")

        assert result.hint == ""
        assert result.tokens == []


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
