package com.dartwatch.collection;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import com.dartwatch.scheduling.SchedulerReadiness;
import com.dartwatch.scheduling.SchedulingConfiguration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CollectionSchedulerTests {
    private final CollectionPipelineService service = mock(CollectionPipelineService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(CollectionSchedulingConfiguration.class, SchedulingConfiguration.class)
            .withBean(CollectionPipelineService.class, () -> service);

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
    void scheduledCallbackDelegatesToTheCollectionPipeline() {
        when(service.run()).thenReturn(completed());
        var readiness = new SchedulerReadiness();
        readiness.markReady();
        new CollectionScheduler(service, readiness).collect();
        verify(service).run();
    }

    @Test
    void configuredIntervalActuallyTriggersRepeatedScheduledExecution() {
        when(service.run()).thenReturn(completed());
        runner.withPropertyValues("dartwatch.collection.enabled=true", "dartwatch.collection.interval=PT0.02S",
                "dartwatch.collection.initial-delay=PT0S").run(context -> {
                    context.getBean(SchedulerReadiness.class).markReady();
                    verify(service, timeout(2000).atLeast(2)).run();
                });
    }

    @Test
    void ignoresTicksBeforeApplicationReady() {
        new CollectionScheduler(service, new SchedulerReadiness()).collect();
        verifyNoInteractions(service);
    }

    @Test
    void exceptionDoesNotCancelTheNextScheduledExecution() {
        when(service.run()).thenThrow(new IllegalStateException("unavailable")).thenReturn(completed());
        runner.withPropertyValues("dartwatch.collection.enabled=true", "dartwatch.collection.interval=PT0.02S",
                "dartwatch.collection.initial-delay=PT0S").run(context -> {
            context.getBean(SchedulerReadiness.class).markReady();
            verify(service, timeout(2000).atLeast(2)).run();
        });
    }

    static CollectionPipelineService.Result completed() {
        return new CollectionPipelineService.Result("COMPLETED",
                new DisclosureCollectionService.CollectionResult("COMPLETED", List.of()), List.of());
    }
}
