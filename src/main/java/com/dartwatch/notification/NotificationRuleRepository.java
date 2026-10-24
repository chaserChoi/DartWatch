package com.dartwatch.notification;

import java.util.List;
import java.util.Optional;
import com.dartwatch.disclosure.DisclosureType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRuleRepository extends JpaRepository<NotificationRule, Long> {
    List<NotificationRule> findByWatchedCompanyIdAndEnabledTrue(Long watchedCompanyId);
    List<NotificationRule> findByWatchedCompanyIdOrderByIdAsc(Long watchedCompanyId);
    Optional<NotificationRule> findByWatchedCompanyIdAndDisclosureTypeAndChannel(
            Long watchId, DisclosureType type, NotificationChannel channel);
}
