package com.borderless.proxy.glossary.repository;

import com.borderless.proxy.glossary.entity.Glossary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GlossaryRepository extends JpaRepository<Glossary, Long> {
    List<Glossary> findByTeamId(Long teamId);
}
