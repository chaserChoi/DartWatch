package com.dartwatch.notification;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {
    Optional<NotificationDelivery> findByDisclosureIdAndChannel(Long disclosureId, NotificationChannel channel);
    Page<NotificationDelivery> findByStatusOrderByCreatedAtAsc(NotificationStatus status, Pageable pageable);
    @Override
    @EntityGraph(attributePaths = {"disclosure", "disclosure.company"})
    Page<NotificationDelivery> findAll(Pageable pageable);
    @EntityGraph(attributePaths = {"disclosure", "disclosure.company"})
    Page<NotificationDelivery> findByStatus(NotificationStatus status, Pageable pageable);
}
