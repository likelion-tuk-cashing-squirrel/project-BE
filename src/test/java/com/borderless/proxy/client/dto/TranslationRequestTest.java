package com.borderless.proxy.client.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TranslationRequestTest {

    @Test
    @DisplayName("언어 코드는 대문자로 정규화된다 - DeepL은 대문자를 요구한다")
    void langCodeIsUpperCased() {
        TranslationRequest request = TranslationRequest.of("hello", "ko", "en-us");

        assertThat(request.getSourceLang()).isEqualTo("KO");
        assertThat(request.getTargetLang()).isEqualTo("EN-US");
    }

    @Test
    @DisplayName("언어 코드 앞뒤 공백은 제거된다")
    void langCodeIsTrimmed() {
        TranslationRequest request = TranslationRequest.of("hello", "  ko  ", " EN ");

        assertThat(request.getSourceLang()).isEqualTo("KO");
        assertThat(request.getTargetLang()).isEqualTo("EN");
    }

    @Test
    @DisplayName("sourceLang을 생략하면 null이다 - DeepL 자동 감지에 맡긴다")
    void sourceLangIsNullable() {
        TranslationRequest request = TranslationRequest.of("hello", "KO");

        assertThat(request.getSourceLang()).isNull();
        assertThat(request.getTargetLang()).isEqualTo("KO");
    }

    @Test
    @DisplayName("sourceLang이 빈 문자열이어도 null로 처리된다")
    void blankSourceLangBecomesNull() {
        TranslationRequest request = TranslationRequest.of("hello", "   ", "KO");

        assertThat(request.getSourceLang()).isNull();
    }

    @Test
    @DisplayName("texts가 비어 있으면 예외가 발생한다")
    void emptyTexts() {
        assertThatThrownBy(() -> TranslationRequest.builder()
                .texts(List.of())
                .targetLang("EN")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("texts");
    }

    @Test
    @DisplayName("targetLang이 비어 있으면 예외가 발생한다")
    void blankTargetLang() {
        assertThatThrownBy(() -> TranslationRequest.of("hello", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetLang");
    }

    @Test
    @DisplayName("texts는 방어적으로 복사되어 외부 변경에 영향받지 않는다")
    void textsAreDefensivelyCopied() {
        List<String> mutable = new ArrayList<>(List.of("첫 번째"));

        TranslationRequest request = TranslationRequest.builder()
                .texts(mutable)
                .targetLang("EN")
                .build();

        mutable.add("나중에 추가");

        assertThat(request.getTexts()).hasSize(1);
        assertThat(request.getTexts()).containsExactly("첫 번째");
    }

    @Test
    @DisplayName("preserveMaskedTokens 기본값은 false다")
    void preserveMaskedTokensDefaultsToFalse() {
        assertThat(TranslationRequest.of("hello", "KO").isPreserveMaskedTokens()).isFalse();
    }

    @Test
    @DisplayName("Formality enum은 DeepL 파라미터 값을 가진다")
    void formalityValues() {
        assertThat(TranslationRequest.Formality.PREFER_MORE.getValue()).isEqualTo("prefer_more");
        assertThat(TranslationRequest.Formality.LESS.getValue()).isEqualTo("less");
    }
}
