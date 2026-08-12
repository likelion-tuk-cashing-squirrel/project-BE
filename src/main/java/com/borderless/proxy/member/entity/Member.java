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

    // 물리적 FK 제약조건 생성 방지
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private AuthProvider provider;

    @Column(nullable = false)
    private String providerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(length = 10)
    private String nativeLang;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}