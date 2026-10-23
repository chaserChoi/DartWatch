package com.dartwatch.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByDisclosureIdAndChannel(Long disclosureId, NotificationChannel channel);
    Page<NotificationDelivery> findByStatusOrderByCreatedAtAsc(NotificationStatus status, Pageable pageable);
}
