package com.borderless.proxy.client.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 형태소 사이드카({@code POST /morphology/hints})의 응답.
 *
 * <p>{@code hint}는 LLM 프롬프트에 그대로 붙일 수 있는 한 줄 문장이다.
 * 접사가 붙은 단어가 없으면 빈 문자열이며, 그때는 프롬프트에 아무것도 붙이지 않으면 된다.
 *
 * <p>{@code tokens}는 단어별 분해 내역으로 로깅·시연용이다. 프롬프트에는 쓰지 않는다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MorphologyHints {

    /** 분석 대상 원문. 사이드카는 텍스트를 변형하지 않는다. */
    private final String text;

    /** 프롬프트에 붙일 힌트 문장. 힌트가 없으면 빈 문자열 */
    private final String hint;

    private final List<TokenHint> tokens;

    @Builder
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public MorphologyHints(@JsonProperty("text") String text,
                           @JsonProperty("hint") String hint,
                           @JsonProperty("tokens") List<TokenHint> tokens) {
        this.text = text;
        this.hint = hint == null ? "" : hint;
        this.tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }

    /** 힌트를 붙일 게 없는 상태. 사이드카를 호출하지 않았거나 실패했을 때 쓴다. */
    public static MorphologyHints empty(String text) {
        return new MorphologyHints(text, "", List.of());
    }

    /** 프롬프트에 붙일 힌트가 있는지. 없으면 호출부는 원문만 넘기면 된다. */
    public boolean hasHint() {
        return !hint.isBlank();
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenHint {

        private final String token;
        private final String stem;
        private final String prefix;
        private final String infix;
        private final String suffix;
        private final String reduplication;

        @Builder
        @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
        public TokenHint(@JsonProperty("token") String token,
                         @JsonProperty("stem") String stem,
                         @JsonProperty("prefix") String prefix,
                         @JsonProperty("infix") String infix,
                         @JsonProperty("suffix") String suffix,
                         @JsonProperty("reduplication") String reduplication) {
            this.token = token;
            this.stem = stem;
            this.prefix = prefix;
            this.infix = infix;
            this.suffix = suffix;
            this.reduplication = reduplication;
        }
    }
}
