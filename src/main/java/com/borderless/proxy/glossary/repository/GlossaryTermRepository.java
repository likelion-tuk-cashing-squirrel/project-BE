package com.borderless.proxy.glossary.repository;

import com.borderless.proxy.glossary.entity.GlossaryTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface GlossaryTermRepository extends JpaRepository<GlossaryTerm, Long> {
    List<GlossaryTerm> findByGlossaryId(Long glossaryId);

    @Query("SELECT gt FROM GlossaryTerm gt JOIN gt.glossary g WHERE g.team.id = :teamId")
    List<GlossaryTerm> findAllByTeamId(@Param("teamId") Long teamId);
}
