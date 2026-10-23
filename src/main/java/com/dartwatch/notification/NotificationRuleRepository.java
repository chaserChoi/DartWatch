package com.dartwatch.notification;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {
    List<NotificationRule> findByWatchedCompanyIdAndEnabledTrue(Long watchedCompanyId);
}
