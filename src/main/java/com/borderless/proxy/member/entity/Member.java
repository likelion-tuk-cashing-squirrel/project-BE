package com.borderless.proxy.member.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "member")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class Member {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuthProvider provider;

    @Column(nullable = false)
    private String providerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 10)
    private String nativeLang;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public Member(AuthProvider provider, String providerId, String name, String nativeLang) {
        this.provider = provider;
        this.providerId = providerId;
        this.name = name;
        this.nativeLang = nativeLang;
    }
}