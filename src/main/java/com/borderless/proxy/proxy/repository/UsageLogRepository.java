package com.borderless.proxy.proxy.repository;

import com.borderless.proxy.proxy.entity.UsageLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageLogRepository extends JpaRepository<UsageLog, Long> {
}
