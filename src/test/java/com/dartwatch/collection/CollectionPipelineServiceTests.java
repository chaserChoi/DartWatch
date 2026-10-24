package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.DisclosureClassificationService;
import com.dartwatch.notification.NotificationDispatchService;
import com.dartwatch.notification.NotificationProperties;
import com.dartwatch.opendart.OpenDartException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollectionPipelineServiceTests {
    @Mock DisclosureCollectionService collector;
    @Mock DisclosureClassificationService classifier;
    @Mock NotificationDispatchService dispatcher;
    private final NotificationProperties properties = new NotificationProperties();
    private CollectionPipelineService pipeline;

    @BeforeEach
    void configure() { pipeline = new CollectionPipelineService(collector, classifier, dispatcher, properties); }

    @Test
    void followsOnlySuccessfulCompaniesInOrderAndNeverSendsMailDirectly() {
        properties.setEnabled(true);
        when(collector.collectAll()).thenReturn(result("PARTIAL_FAILED", success(1L), failed(2L, "UPSTREAM_SYSTEM"),
                new CollectionStore.Outcome(3L, "SKIPPED", null, null, 0, 0, null)));
        when(classifier.classify(1L)).thenReturn(classified());
        when(dispatcher.enqueue(1L)).thenReturn(1);
        var run = pipeline.run();
        assertThat(run.followUps()).hasSize(1);
        var order = inOrder(collector, classifier, dispatcher);
        order.verify(collector).collectAll();
        order.verify(classifier).classify(1L);
        order.verify(dispatcher).enqueue(1L);
        verify(classifier, never()).classify(2L);
        verify(classifier, never()).classify(3L);
        verify(dispatcher, never()).dispatch();
    }

    @Test
    void disabledNotificationsStillClassifyButDoNotGenerateJobs() {
        when(collector.collectAll()).thenReturn(result("COMPLETED", success(1L)));
        when(classifier.classify(1L)).thenReturn(classified());
        assertThat(pipeline.run().status()).isEqualTo("COMPLETED");
        verifyNoInteractions(dispatcher);
    }

    @Test
    void classificationFailureSkipsEnqueueButAllowsOtherCompanies() {
        properties.setEnabled(true);
        when(collector.collectAll()).thenReturn(result("COMPLETED", success(1L), success(2L)));
        when(classifier.classify(1L)).thenThrow(new OpenDartException(OpenDartException.Kind.UPSTREAM_SYSTEM));
        when(classifier.classify(2L)).thenReturn(classified());
        var run = pipeline.run();
        assertThat(run.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(run.followUps().getFirst().status()).isEqualTo("CLASSIFICATION_FAILED");
        verify(dispatcher, never()).enqueue(1L);
        verify(dispatcher).enqueue(2L);
    }

    @Test
    void quotaFailureInCollectionStopsAdditionalTypeApiCalls() {
        when(collector.collectAll()).thenReturn(result("PARTIAL_FAILED", success(1L), failed(2L, "RATE_LIMIT")));
        assertThat(pipeline.run().followUps().getFirst().error()).isEqualTo("API_BLOCKED");
        verifyNoInteractions(classifier, dispatcher);
    }

    @Test
    void authenticationFailureInClassificationStopsRemainingCompanies() {
        when(collector.collectAll()).thenReturn(result("COMPLETED", success(1L), success(2L)));
        when(classifier.classify(1L)).thenThrow(new OpenDartException(OpenDartException.Kind.AUTHENTICATION));
        var run = pipeline.run();
        assertThat(run.followUps().get(1).error()).isEqualTo("API_BLOCKED");
        verify(classifier, never()).classify(2L);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void jobPersistenceFailureDoesNotRerunCollectionOrExposeDiagnostics() {
        properties.setEnabled(true);
        when(collector.collectAll()).thenReturn(result("COMPLETED", success(1L)));
        when(classifier.classify(1L)).thenReturn(classified());
        when(dispatcher.enqueue(1L)).thenThrow(new DataAccessResourceFailureException("private diagnostic"));
        var run = pipeline.run();
        assertThat(run.followUps().getFirst().error()).isEqualTo("PERSISTENCE");
        verify(collector, times(1)).collectAll();
        verify(dispatcher, never()).dispatch();
    }

    @Test
    void guardCoversFollowUpStagesAndIsReleasedWhenTheyFinish() {
        when(collector.collectAll()).thenReturn(result("COMPLETED", success(1L)));
        when(classifier.classify(1L)).thenAnswer(invocation -> {
            assertThatThrownBy(pipeline::run).isInstanceOf(ApiException.class);
            return classified();
        });
        pipeline.run();
        pipeline.run();
        verify(collector, times(2)).collectAll();
    }

    @Test
    void completeCollectionFailureHasNoFollowUp() {
        when(collector.collectAll()).thenReturn(result("FAILED", failed(1L, "TIMEOUT")));
        assertThat(pipeline.run().status()).isEqualTo("FAILED");
        verifyNoInteractions(classifier, dispatcher);
    }

    private static DisclosureClassificationService.Result classified() {
        return new DisclosureClassificationService.Result(1, 1, 0, 1);
    }
    private static CollectionStore.Outcome success(Long id) { return new CollectionStore.Outcome(id, "SUCCESS", null, null, 1, 1, null); }
    private static CollectionStore.Outcome failed(Long id, String code) { return new CollectionStore.Outcome(id, "FAILED", null, null, 0, 0, code); }
    private static DisclosureCollectionService.CollectionResult result(String status, CollectionStore.Outcome... outcomes) {
        return new DisclosureCollectionService.CollectionResult(status, List.of(outcomes));
    }
}
