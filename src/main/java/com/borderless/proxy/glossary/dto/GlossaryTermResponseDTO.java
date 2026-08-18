package com.borderless.proxy.glossary.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GlossaryTermResponseDTO {
    private Long id;
    private String sourceTerm;
    private String translation;
}