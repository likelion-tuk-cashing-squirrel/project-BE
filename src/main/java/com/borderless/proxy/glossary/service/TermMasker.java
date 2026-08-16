package com.borderless.proxy.glossary.service;

import com.borderless.proxy.glossary.dto.MaskingResultDTO;
import com.borderless.proxy.glossary.entity.GlossaryTerm;
import com.borderless.proxy.glossary.repository.GlossaryTermRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermMasker {

    private final GlossaryTermRepository glossaryTermRepository;

    public MaskingResultDTO maskText(Long teamId, String originalText) {

        List<GlossaryTerm> terms = glossaryTermRepository.findAllByTeamId(teamId);

        terms.sort((t1, t2) -> t2.getSourceTerm().length() - t1.getSourceTerm().length());

        String maskedText = originalText;
        Map<String, String> dictionary = new HashMap<>();

        for (GlossaryTerm term : terms) {
            String source = term.getSourceTerm();

            if (maskedText.contains(source)) {
                maskedText = maskedText.replace(source, term.getMaskedToken());
                dictionary.put(term.getMaskedToken(), term.getTranslation());
            }
        }

        return new MaskingResultDTO(maskedText, dictionary);
    }
}