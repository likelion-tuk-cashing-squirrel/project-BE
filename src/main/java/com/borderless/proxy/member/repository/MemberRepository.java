package com.borderless.proxy.member.repository;

import com.borderless.proxy.member.entity.AuthProvider;
import com.borderless.proxy.member.entity.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MemberRepository extends JpaRepository<Member, Long> {
    Optional<Member> findByProviderAndProviderId(AuthProvider provider, String providerId);
}