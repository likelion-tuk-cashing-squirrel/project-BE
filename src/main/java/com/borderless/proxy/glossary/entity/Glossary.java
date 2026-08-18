package com.borderless.proxy.glossary.entity;

import com.borderless.proxy.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "glossary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Glossary {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Member member;

    @Column(nullable = false, length = 100)
    private String namespace;

    private String description;

    @Builder
    public Glossary(Member member, String namespace, String description) {
        this.member = member;
        this.namespace = namespace;
        this.description = description;
    }
}