package com.dartwatch.notification;

import com.dartwatch.collection.*;
import com.dartwatch.scheduling.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationSchedulerTests {
    private final NotificationDispatchService dispatcher = mock(NotificationDispatchService.class);
    private final CollectionPipelineService pipeline = mock(CollectionPipelineService.class);
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfiguration.class, NotificationSchedulingConfiguration.class,
                    CollectionSchedulingConfiguration.class)
            .withBean(NotificationDispatchService.class, () -> dispatcher)
            .withBean(CollectionPipelineService.class, () -> pipeline);

    @Test
    void disabledByDefaultAndRequiresBothNotificationFlags() {
        runner.run(context -> assertThat(context).doesNotHaveBean(NotificationScheduler.class));
        runner.withPropertyValues("dartwatch.notification.enabled=true").run(context ->
                assertThat(context).doesNotHaveBean(NotificationScheduler.class));
        runner.withPropertyValues("dartwatch.notification.scheduler.enabled=true", "dartwatch.notification.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(NotificationScheduler.class));
        verifyNoInteractions(dispatcher, pipeline);
    }

    @Test
    void waitsForApplicationReadyThenExecutesRepeatedlyEvenWithoutCollectionScheduler() {
        when(dispatcher.dispatch()).thenReturn(completed());
        runner.withPropertyValues("dartwatch.notification.enabled=true", "dartwatch.notification.scheduler.enabled=true",
                "dartwatch.notification.scheduler.interval=PT0.02S", "dartwatch.notification.scheduler.initial-delay=PT0S")
                .run(context -> {
                    assertThat(context).hasSingleBean(NotificationScheduler.class).doesNotHaveBean(CollectionScheduler.class);
                    verifyNoInteractions(dispatcher);
                    context.getBean(SchedulerReadiness.class).markReady();
                    verify(dispatcher, timeout(2000).atLeast(2)).dispatch();
                });
    }

    @Test
    void callbackBeforeReadyNeverDispatches() {
        new NotificationScheduler(dispatcher, new SchedulerReadiness()).dispatch();
        verifyNoInteractions(dispatcher);
    }

    @Test
    void blockingCollectionDoesNotBlockNotificationTimer() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pipeline.run()).thenAnswer(invocation -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return new CollectionPipelineService.Result("COMPLETED",
                    new DisclosureCollectionService.CollectionResult("COMPLETED", List.of()), List.of());
        });
        when(dispatcher.dispatch()).thenReturn(completed());
        runner.withPropertyValues("dartwatch.notification.enabled=true", "dartwatch.notification.scheduler.enabled=true",
                "dartwatch.notification.scheduler.interval=PT0.02S", "dartwatch.notification.scheduler.initial-delay=PT0S",
                "dartwatch.collection.enabled=true", "dartwatch.collection.interval=PT0.02S", "dartwatch.collection.initial-delay=PT0.02S")
                .run(context -> {
                    try {
                        context.getBean(SchedulerReadiness.class).markReady();
                        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                        clearInvocations(dispatcher);
                        verify(dispatcher, timeout(1000).atLeastOnce()).dispatch();
                        assertThat(release.getCount()).isEqualTo(1);
                    } finally { release.countDown(); }
                });
    }

    private static NotificationDispatchService.DispatchResult completed() {
        return new NotificationDispatchService.DispatchResult(0, 0, 0, 0, 0);
    }
}
