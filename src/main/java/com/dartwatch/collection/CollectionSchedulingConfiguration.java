package com.dartwatch.collection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.dartwatch.scheduling.SchedulerReadiness;

@Configuration
@ConditionalOnProperty(prefix = "dartwatch.collection", name = "enabled", havingValue = "true")
public class CollectionSchedulingConfiguration {
    @Bean
    CollectionScheduler collectionScheduler(CollectionPipelineService service, SchedulerReadiness readiness) {
        return new CollectionScheduler(service, readiness);
    }
}
