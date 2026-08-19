package com.borderless.proxy.billing.repository;

import com.borderless.proxy.proxy.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {

    @Query("""
        select coalesce(sum(u.savedTokens), 0)
        from UsageLog u
        join u.request r
        where r.member.id = :memberId
    """)
    long sumSavedTokensByMemberId(@Param("memberId") Long memberId);

    @Query("""
        select coalesce(sum(u.savedCostUsd), 0)
        from UsageLog u
        join u.request r
        where r.member.id = :memberId
    """)
    BigDecimal sumSavedCostUsdByMemberId(@Param("memberId") Long memberId);
}