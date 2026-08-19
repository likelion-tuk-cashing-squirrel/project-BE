package com.borderless.proxy.client.dto.deepl;

import com.borderless.proxy.client.dto.TranslationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepL로 실제 전송되는 JSON 모양 검증
 * source_lang 생략을 어기면 400이 발생하는데 원인 파악이 오래 걸리는 항목
 */
class DeepLTranslateRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("sourceLang이 null이면 source_lang 필드 자체가 전송되지 않는다 - 자동 감지에 필요")
    void sourceLangIsOmittedWhenNull() {
        TranslationRequest source = TranslationRequest.of("안녕하세요", "EN");

        String json = mapper.writeValueAsString(DeepLTranslateRequest.from(source));

        assertThat(json).doesNotContain("source_lang");
        assertThat(json).doesNotContain("null");
    }

    @Test
    @DisplayName("sourceLang을 지정하면 대문자로 전송된다")
    void sourceLangIsSent() {
        TranslationRequest source = TranslationRequest.of("안녕하세요", "ko", "EN");

        String json = mapper.writeValueAsString(DeepLTranslateRequest.from(source));

        assertThat(json).contains("\"source_lang\":\"KO\"");
        assertThat(json).contains("\"target_lang\":\"EN\"");
    }

    @Test
    @DisplayName("단건 번역도 text는 배열로 전송된다 - DeepL 스펙")
    void textIsAlwaysArray() {
        String json = mapper.writeValueAsString(
                DeepLTranslateRequest.from(TranslationRequest.of("안녕하세요", "EN")));

        assertThat(json).contains("\"text\":[\"안녕하세요\"]");
    }

    @Test
    @DisplayName("여러 문장을 순서대로 담는다")
    void multipleTexts() {
        TranslationRequest source = TranslationRequest.builder()
                .texts(List.of("첫째", "둘째", "셋째"))
                .targetLang("EN")
                .build();

        DeepLTranslateRequest request = DeepLTranslateRequest.from(source);

        assertThat(request.getText()).containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    @DisplayName("preserveMaskedTokens=true면 tag_handling과 ignore_tags가 붙는다")
    void maskedTokenProtectionEnabled() {
        TranslationRequest source = TranslationRequest.builder()
                .texts(List.of("<x>{TERM_01}</x>은 좋은 제품입니다"))
                .targetLang("EN")
                .preserveMaskedTokens(true)
                .build();

        String json = mapper.writeValueAsString(DeepLTranslateRequest.from(source));

        assertThat(json).contains("\"tag_handling\":\"xml\"");
        assertThat(json).contains("\"ignore_tags\":[\"x\"]");
    }

    @Test
    @DisplayName("preserveMaskedTokens=false면 태그 관련 필드가 전송되지 않는다")
    void maskedTokenProtectionDisabled() {
        String json = mapper.writeValueAsString(
                DeepLTranslateRequest.from(TranslationRequest.of("hello", "KO")));

        assertThat(json).doesNotContain("tag_handling");
        assertThat(json).doesNotContain("ignore_tags");
    }

    @Test
    @DisplayName("과금 문자 수는 항상 요청한다 - usage_log 기록에 필요")
    void alwaysRequestsBilledCharacters() {
        String json = mapper.writeValueAsString(
                DeepLTranslateRequest.from(TranslationRequest.of("hello", "KO")));

        assertThat(json).contains("\"show_billed_characters\":true");
        assertThat(json).contains("\"preserve_formatting\":true");
        assertThat(json).doesNotContain("showBilledCharacters");
    }

    @Test
    @DisplayName("formality를 지정하면 DeepL 파라미터 값으로 변환된다")
    void formality() {
        TranslationRequest source = TranslationRequest.builder()
                .texts(List.of("hello"))
                .targetLang("KO")
                .formality(TranslationRequest.Formality.PREFER_MORE)
                .build();

        String json = mapper.writeValueAsString(DeepLTranslateRequest.from(source));

        assertThat(json).contains("\"formality\":\"prefer_more\"");
    }
}
