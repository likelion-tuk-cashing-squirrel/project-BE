package com.borderless.proxy.glossary.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class GlossaryTermCreateRequestDTO {
    private String sourceTerm;
    private String translation;
}