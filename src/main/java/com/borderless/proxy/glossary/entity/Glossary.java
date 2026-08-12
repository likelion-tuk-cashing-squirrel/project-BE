package com.borderless.proxy.glossary.entity;

import com.borderless.proxy.member.entity.Team;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "glossary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Glossary {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Team team;

    @Column(nullable = false, length = 100)
    private String namespace;

    private String description;
}
