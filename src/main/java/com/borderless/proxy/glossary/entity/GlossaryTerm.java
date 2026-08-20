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

    @Builder
    public GlossaryTerm(Glossary glossary, String sourceTerm, String maskedToken, String translation) {
        this.glossary = glossary;
        this.sourceTerm = sourceTerm;
        this.maskedToken = maskedToken;
        this.translation = translation;
    }

    /**
     * 치환 토큰을 확정한다.
     *
     * <p>토큰을 PK에서 만들기 때문에 INSERT 이후에만 값을 정할 수 있어 이 메서드가 필요하다.
     * {@code masked_token}이 {@code nullable = false}라 INSERT 시점에는 임시값이 들어가고,
     * 같은 트랜잭션에서 이 메서드로 덮어쓴다.
     *
     * <p>한 번 확정된 토큰은 바꾸지 않는다. 마스킹 사전과 시스템 프롬프트가 이 값을 키로 쓰므로
     * 도중에 바뀌면 진행 중인 요청의 복원이 깨진다.
     */
    public void assignMaskedToken(String maskedToken) {
        if (maskedToken == null || maskedToken.isBlank()) {
            throw new IllegalArgumentException("maskedToken은 null이거나 공백일 수 없습니다.");
        }
        this.maskedToken = maskedToken;
    }
}
