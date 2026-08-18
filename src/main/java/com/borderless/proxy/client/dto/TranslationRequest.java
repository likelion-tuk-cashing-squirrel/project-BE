package com.borderless.proxy.client.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * TranslationClient에 전달하는 벤더 중립 번역 요청 DTO
 * <p>
 * DeepL의 text 필드가 배열이므로 내부에서도 리스트로 처리
 * 피벗(KO -> EN -> XX) 여부는 routing 파트가 판단하며 이 DTO는 관여하지 않음
 * 피벗이 필요하면 오케스트레이터가 이 DTO로 2회 연속 호출
 */
@Getter
public class TranslationRequest {

    /** 번역할 텍스트 목록. 비어 있을 수 없음 (이미 마스킹이 끝난 텍스트) */
    private final List<String> texts;

    /** 출발 언어. null이면 DeepL 자동 감지 */
    private final String sourceLang;

    /** 도착 언어. 필수 */
    private final String targetLang;

    /** 격식 수준. nullable */
    private final Formality formality;

    /**
     * 용어집 마스킹 토큰을 번역기가 건드리지 않도록 보호할지 여부
     * <p>
     * true면 DeepL 요청에 tag_handling=xml, ignore_tags=x 적용
     * 단, 토큰을 &lt;x&gt;...&lt;/x&gt; 로 감싸는 책임은 상위 계층(glossary/proxy)에 있음
     * TODO: 토큰 래핑 규약을 glossary 파트와 확정할 것
     */
    private final boolean preserveMaskedTokens;

    @Builder
    public TranslationRequest(List<String> texts, String sourceLang, String targetLang,
                              Formality formality, boolean preserveMaskedTokens) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts는 비어 있을 수 없습니다.");
        }
        if (targetLang == null || targetLang.isBlank()) {
            throw new IllegalArgumentException("targetLang은 비어 있을 수 없습니다.");
        }
        this.texts = List.copyOf(texts);
        this.sourceLang = normalize(sourceLang);
        this.targetLang = normalize(targetLang);
        this.formality = formality;
        this.preserveMaskedTokens = preserveMaskedTokens;
    }

    private static String normalize(String lang) {
        return (lang == null || lang.isBlank()) ? null : lang.trim().toUpperCase();
    }

    /** 단건 번역 편의 생성자 */
    public static TranslationRequest of(String text, String targetLang) {
        return TranslationRequest.builder()
                .texts(List.of(text))
                .targetLang(targetLang)
                .build();
    }

    public static TranslationRequest of(String text, String sourceLang, String targetLang) {
        return TranslationRequest.builder()
                .texts(List.of(text))
                .sourceLang(sourceLang)
                .targetLang(targetLang)
                .build();
    }

    /** DeepL formality 파라미터 값 */
    public enum Formality {
        DEFAULT("default"),
        MORE("more"),
        LESS("less"),
        PREFER_MORE("prefer_more"),
        PREFER_LESS("prefer_less");

        private final String value;

        Formality(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }
}
