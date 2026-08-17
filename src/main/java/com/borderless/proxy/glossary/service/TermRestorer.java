package com.borderless.proxy.glossary.service;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class TermRestorer {

    public String restoreText(String translatedText, Map<String, String> dictionary) {
        if (dictionary == null || dictionary.isEmpty()) {
            return translatedText;
        }

        String restoredText = translatedText;

        for (Map.Entry<String, String> entry : dictionary.entrySet()) {
            String token = entry.getKey();
            String translation = entry.getValue();

            if (restoredText.contains(token)) {
                restoredText = restoredText.replace(token, translation);
            }
        }

        return restoredText;
    }
}