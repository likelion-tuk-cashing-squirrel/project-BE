package com.borderless.proxy.glossary.repository;

import com.borderless.proxy.glossary.entity.GlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {
    List<GlossaryTerm> findByGlossaryId(Long glossaryId);
}
