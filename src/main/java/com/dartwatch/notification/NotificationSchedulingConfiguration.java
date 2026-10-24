package com.dartwatch.notification;

import com.dartwatch.scheduling.SchedulerReadiness;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "dartwatch.notification", name = {"enabled", "scheduler.enabled"}, havingValue = "true")
public class NotificationSchedulingConfiguration {
    @Bean
    NotificationScheduler notificationScheduler(NotificationDispatchService dispatcher, SchedulerReadiness readiness) {
        return new NotificationScheduler(dispatcher, readiness);
    }
}
