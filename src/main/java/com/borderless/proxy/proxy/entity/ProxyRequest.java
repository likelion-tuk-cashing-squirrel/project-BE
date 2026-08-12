package com.borderless.proxy.proxy.entity;

import com.borderless.proxy.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import java.time.LocalDateTime;

@Entity
@Table(name = "proxy_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class ProxyRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Member member;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String originalText;

    @Column(nullable = false, length = 10)
    private String sourceLang;

    @Column(nullable = false)
    private Boolean isPivoted;

    @Column(nullable = false, length = 20)
    private String routeDecision;

    @Column(nullable = false, length = 20)
    private String status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}