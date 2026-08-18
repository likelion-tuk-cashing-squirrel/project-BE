package com.borderless.proxy.client.dto.deepl;

import com.borderless.proxy.client.dto.TranslationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class DeepLTranslateResponseTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    private static final String SAMPLE = """
            {
              "translations": [
                {
                  "text": "Hello",
                  "detected_source_language": "KO",
                  "billed_characters": 5,
                  "model_type_used": "quality_optimized"
                }
              ]
            }
            """;

    @Test
    @DisplayName("스네이크 케이스 응답이 DTO로 파싱된다")
    void deserialize() {
        DeepLTranslateResponse response = mapper.readValue(SAMPLE, DeepLTranslateResponse.class);

        assertThat(response.getTranslations()).hasSize(1);
        assertThat(response.getTranslations().get(0).getText()).isEqualTo("Hello");
        assertThat(response.getTranslations().get(0).getDetectedSourceLanguage()).isEqualTo("KO");
        assertThat(response.getTranslations().get(0).getBilledCharacters()).isEqualTo(5);
        assertThat(response.getTranslations().get(0).getModelTypeUsed()).isEqualTo("quality_optimized");
    }

    @Test
    @DisplayName("내부 DTO로 변환하면 본문과 과금 정보가 함께 넘어온다")
    void toTranslationResponse() {
        TranslationResponse result = mapper.readValue(SAMPLE, DeepLTranslateResponse.class)
                                           .toTranslationResponse(250);

        assertThat(result.getFirstText()).isEqualTo("Hello");
        assertThat(result.getTranslations().get(0).getDetectedSourceLang()).isEqualTo("KO");
        assertThat(result.getTotalBilledCharacters()).isEqualTo(5);
        assertThat(result.getLatencyMs()).isEqualTo(250);
    }

    @Test
    @DisplayName("여러 문장의 순서가 요청과 1:1로 유지된다")
    void preservesOrder() {
        String json = """
                {
                  "translations": [
                    { "text": "First",  "detected_source_language": "KO", "billed_characters": 5 },
                    { "text": "Second", "detected_source_language": "KO", "billed_characters": 6 },
                    { "text": "Third",  "detected_source_language": "KO", "billed_characters": 5 }
                  ]
                }
                """;

        TranslationResponse result = mapper.readValue(json, DeepLTranslateResponse.class)
                                           .toTranslationResponse(100);

        assertThat(result.getTexts()).containsExactly("First", "Second", "Third");
        assertThat(result.getTotalBilledCharacters()).isEqualTo(16);
    }

    @Test
    @DisplayName("billed_characters가 없어도 합계 계산에서 터지지 않는다")
    void nullBilledCharacters() {
        String json = """
                { "translations": [ { "text": "Hello", "detected_source_language": "KO" } ] }
                """;

        TranslationResponse result = mapper.readValue(json, DeepLTranslateResponse.class)
                                           .toTranslationResponse(100);

        assertThat(result.getTotalBilledCharacters()).isZero();
        assertThat(result.getFirstText()).isEqualTo("Hello");
    }

    @Test
    @DisplayName("translations가 비어 있어도 안전하게 처리된다")
    void emptyTranslations() {
        DeepLTranslateResponse response =
                mapper.readValue("{\"translations\":[]}", DeepLTranslateResponse.class);

        assertThat(response.isEmpty()).isTrue();
        assertThat(response.toTranslationResponse(10).getFirstText()).isNull();
    }

    @Test
    @DisplayName("DeepL이 새 필드를 추가해도 파싱이 깨지지 않는다")
    void toleratesFutureFields() {
        String json = """
                {
                  "translations": [
                    { "text": "Hello", "detected_source_language": "KO", "brand_new_field": 123 }
                  ],
                  "top_level_new_field": "whatever"
                }
                """;

        assertThat(mapper.readValue(json, DeepLTranslateResponse.class).getTranslations()).hasSize(1);
    }
}
