package com.borderless.proxy.glossary.repository;

import com.borderless.proxy.glossary.entity.GlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {
    List<GlossaryTerm> findByGlossaryId(Long glossaryId);

    @Query("SELECT gt FROM GlossaryTerm gt JOIN gt.glossary g WHERE g.member.id = :memberId")
    List<GlossaryTerm> findAllByMemberId(@Param("memberId") Long memberId);

    Optional<GlossaryTerm> findByGlossaryIdAndSourceTerm(Long glossaryId, String sourceTerm);

    @Query("SELECT gt FROM GlossaryTerm gt JOIN gt.glossary g WHERE gt.id = :termId AND g.member.id = :memberId")
    Optional<GlossaryTerm> findByIdAndMemberId(@Param("termId") Long termId, @Param("memberId") Long memberId);
}