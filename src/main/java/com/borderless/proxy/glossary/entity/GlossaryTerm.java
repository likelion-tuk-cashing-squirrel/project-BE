package com.borderless.proxy.glossary.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "glossary_term")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GlossaryTerm {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "glossary_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Glossary glossary;

    @Column(nullable = false)
    private String sourceTerm;

    @Column(nullable = false, length = 50)
    private String maskedToken;

    private String translation;
}
