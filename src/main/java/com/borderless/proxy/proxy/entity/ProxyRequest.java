package com.borderless.proxy.proxy.entity;

import com.borderless.proxy.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 프록시 요청 한 건. {@link UsageLog}의 부모이며 사용자·원문·라우팅 판정을 담는다.
 *
 * <p>{@code routeDecision}은 {@code RoutingTier}의 이름을 문자열로 저장한다.
 * 엔티티가 routing 패키지에 의존하지 않도록 변환은 호출부({@code UsageRecorder})가 한다.
 */
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

    /** 사용자가 입력한 원문. 마스킹·번역 전 텍스트다. */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String originalText;

    /** 감지된 출발 언어의 ISO 639-1 코드. 특정하지 못하면 "und". */
    @Column(nullable = false, length = 10)
    private String sourceLang;

    /** 영어 피벗을 거쳤는지 여부. */
    @Column(nullable = false)
    private Boolean isPivoted;

    /** 라우터가 결정한 티어 이름(SKIP, TIER_1, TIER_2, TIER_3). */
    @Column(nullable = false, length = 20)
    private String routeDecision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProxyRequestStatus status;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private ProxyRequest(Member member,
                         String originalText,
                         String sourceLang,
                         Boolean isPivoted,
                         String routeDecision,
                         ProxyRequestStatus status) {

        this.member = require(member, "member");
        this.originalText = require(originalText, "originalText");
        this.sourceLang = require(sourceLang, "sourceLang");
        this.isPivoted = require(isPivoted, "isPivoted");
        this.routeDecision = require(routeDecision, "routeDecision");
        this.status = require(status, "status");
    }

    private static <T> T require(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "은 null일 수 없습니다.");
        }
        return value;
    }
}
