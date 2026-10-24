package com.dartwatch.collection;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionSchedulerTests {
    private final DisclosureCollectionService service = mock(DisclosureCollectionService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CollectionSchedulingConfiguration.class)
            .withBean(DisclosureCollectionService.class, () -> service);

    @Test
    void disabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CollectionScheduler.class));
    }

    @Test
    void enabledSchedulerRegistersConfiguredTask() {
        runner.withPropertyValues("dartwatch.collection.enabled=true", "dartwatch.collection.interval=PT1H",
                "dartwatch.collection.initial-delay=PT1H").run(context -> {
            assertThat(context).hasSingleBean(CollectionScheduler.class);
            assertThat(context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
        verifyNoInteractions(service);
    }

    @Test
    void scheduledCallbackDelegatesToTheSharedCollector() {
        when(service.collectAll()).thenReturn(new DisclosureCollectionService.CollectionResult("COMPLETED", List.of()));
        new CollectionScheduler(service).collect();
        verify(service).collectAll();
    }

    @Test
    void configuredIntervalActuallyTriggersRepeatedScheduledExecution() {
        when(service.collectAll()).thenReturn(new DisclosureCollectionService.CollectionResult("COMPLETED", List.of()));
        runner.withPropertyValues("dartwatch.collection.enabled=true", "dartwatch.collection.interval=PT0.02S",
                "dartwatch.collection.initial-delay=PT0S").run(context ->
                verify(service, timeout(2000).atLeast(2)).collectAll());
    }
}
