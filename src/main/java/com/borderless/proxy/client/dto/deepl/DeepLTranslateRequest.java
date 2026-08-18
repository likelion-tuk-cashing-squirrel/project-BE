package com.borderless.proxy.client.dto.deepl;

import com.borderless.proxy.client.dto.TranslationRequest;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * DeepL 번역 요청 바디
 * POST {base-url}/v2/translate
 * NON_NULL 필수: source_lang을 null로 보내면 DeepL이 400을 반환
 * (source_lang을 생략해야 자동 감지로 동작)
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeepLTranslateRequest {

    /** DeepL은 단건 번역도 배열로 전달 */
    private final List<String> text;

    @JsonProperty("target_lang")
    private final String targetLang;

    @JsonProperty("source_lang")
    private final String sourceLang;

    /** "default" | "more" | "less" | "prefer_more" | "prefer_less" */
    private final String formality;

    @JsonProperty("preserve_formatting")
    private final Boolean preserveFormatting;

    /** "xml" | "html". 마스킹 토큰 보호 시 "xml" */
    @JsonProperty("tag_handling")
    private final String tagHandling;

    /** tag_handling=xml일 때 번역에서 제외할 태그 목록 */
    @JsonProperty("ignore_tags")
    private final List<String> ignoreTags;

    /** UsageLog 산정을 위해 항상 true로 요청 */
    @JsonProperty("show_billed_characters")
    private final Boolean showBilledCharacters;

    @Builder
    public DeepLTranslateRequest(List<String> text, String targetLang, String sourceLang, String formality,
                                 Boolean preserveFormatting, String tagHandling, List<String> ignoreTags,
                                 Boolean showBilledCharacters) {
        this.text = text;
        this.targetLang = targetLang;
        this.sourceLang = sourceLang;
        this.formality = formality;
        this.preserveFormatting = preserveFormatting;
        this.tagHandling = tagHandling;
        this.ignoreTags = ignoreTags;
        this.showBilledCharacters = showBilledCharacters;
    }

    /** 마스킹 토큰 보호에 사용할 태그명. 토큰을 &lt;x&gt;...&lt;/x&gt; 로 감싸는 것은 상위 계층 책임 */
    public static final String MASK_TAG = "x";

    /** 내부 DTO -> DeepL 요청 변환 */
    public static DeepLTranslateRequest from(TranslationRequest request) {
        boolean protect = request.isPreserveMaskedTokens();

        return DeepLTranslateRequest.builder()
                .text(request.getTexts())
                .targetLang(request.getTargetLang())
                .sourceLang(request.getSourceLang())   // null이면 NON_NULL로 생략 -> 자동 감지
                .formality(request.getFormality() == null ? null : request.getFormality().getValue())
                .preserveFormatting(Boolean.TRUE)
                .tagHandling(protect ? "xml" : null)
                .ignoreTags(protect ? List.of(MASK_TAG) : null)
                .showBilledCharacters(Boolean.TRUE)
                .build();
    }
}
