package com.dartwatch.collection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "dartwatch.collection", name = "enabled", havingValue = "true")
public class CollectionSchedulingConfiguration {
    @Bean
    CollectionScheduler collectionScheduler(DisclosureCollectionService service) {
        return new CollectionScheduler(service);
    }
}
