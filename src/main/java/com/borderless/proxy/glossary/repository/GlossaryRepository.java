package com.borderless.proxy.glossary.repository;

import com.borderless.proxy.glossary.entity.Glossary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GlossaryRepository extends JpaRepository<Glossary, Long> {
    Optional<Glossary> findByMemberId(Long memberId);
}
