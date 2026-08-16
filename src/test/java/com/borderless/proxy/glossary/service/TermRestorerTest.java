package com.borderless.proxy.glossary.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TermRestorerTest {

    private final TermRestorer termRestorer = new TermRestorer();

    @Test
    @DisplayName("번역된 텍스트의 토큰들이 딕셔너리를 참조하여 원래 번역어로 정상 복원")
    void restoreText_Success() {
        // LLM이 마스킹된 상태로 영어로 번역했다고 가정
        String translatedText = "This project is developed using {TERM_02} and {TERM_01}.";

        Map<String, String> dictionary = Map.of(
                "{TERM_01}", "Spring",
                "{TERM_02}", "Spring Boot"
        );

        String result = termRestorer.restoreText(translatedText, dictionary);

        assertThat(result).isEqualTo("This project is developed using Spring Boot and Spring.");
    }

    @Test
    @DisplayName("딕셔너리가 비어있거나 null이면 원본 텍스트를 그대로 반환")
    void restoreText_EmptyDictionary() {
        String translatedText = "Hello World";
        Map<String, String> emptyDictionary = Map.of();

        String result1 = termRestorer.restoreText(translatedText, emptyDictionary);
        String result2 = termRestorer.restoreText(translatedText, null);

        assertThat(result1).isEqualTo("Hello World");
        assertThat(result2).isEqualTo("Hello World");
    }
}