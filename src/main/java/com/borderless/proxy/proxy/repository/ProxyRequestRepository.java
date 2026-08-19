package com.borderless.proxy.proxy.repository;

import com.borderless.proxy.proxy.entity.ProxyRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProxyRequestRepository extends JpaRepository<ProxyRequest, Long> {
}
