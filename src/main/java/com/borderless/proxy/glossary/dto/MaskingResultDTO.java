package com.borderless.proxy.glossary.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public class MaskingResultDTO {
    private String maskedText;
    private Map<String, String> dictionary;
}
