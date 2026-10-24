package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DisclosureCollectionServiceTests {
    @Mock OpenDartClient client;
    @Mock CollectionStore store;
    @InjectMocks DisclosureCollectionService service;

    @Test
    void onlyTargetsSelectedByTheStoreAreAttemptedAndDisabledRecheckSkips() {
        when(store.activeWatchIds()).thenReturn(List.of(1L));
        when(store.prepare(1L)).thenReturn(null);
        assertThat(service.collectAll().companies()).extracting(CollectionStore.Outcome::status).containsExactly("SKIPPED");
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @EnumSource(value = OpenDartException.Kind.class, names = {"RATE_LIMIT", "AUTHENTICATION", "CONFIGURATION", "INTERRUPTED"})
    void globalFailureIsRecordedAndStopsFurtherCalls(OpenDartException.Kind kind) {
        var date = LocalDate.of(2026, 10, 24);
        var plan = new CollectionStore.Plan(1L, 10L, "00000001", date, date, date, null);
        when(store.activeWatchIds()).thenReturn(List.of(1L, 2L));
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures("00000001", date, date))
                .thenThrow(new OpenDartException(kind));
        assertThat(service.collectAll().status()).isEqualTo("FAILED");
        verify(store).fail(1L, kind.name());
        verify(store, never()).prepare(2L);
        verify(store, never()).complete(any(), any());
    }

    @Test
    void databaseOutageWhileRecordingFailureDoesNotBlockAnotherCompanyOrKeepTheGuard() {
        var date = LocalDate.of(2026, 10, 24);
        var plan = new CollectionStore.Plan(2L, 20L, "00000002", date, date, date, null);
        when(store.activeWatchIds()).thenReturn(List.of(1L, 2L));
        when(store.prepare(1L)).thenThrow(new DataAccessResourceFailureException("sensitive DB detail"));
        doThrow(new DataAccessResourceFailureException("sensitive DB detail")).when(store).fail(1L, "PERSISTENCE");
        when(store.prepare(2L)).thenReturn(plan);
        when(client.findDisclosures("00000002", date, date)).thenReturn(List.of());
        when(store.complete(plan, List.of())).thenReturn(new CollectionStore.Outcome(2L, "SUCCESS", date, date, 0, 0, null));
        var result = service.collectAll();
        assertThat(result.status()).isEqualTo("PARTIAL_FAILED");
        assertThat(result.companies().getFirst().error()).isEqualTo("PERSISTENCE");
        verify(store).complete(plan, List.of());
        when(store.activeWatchIds()).thenReturn(List.of());
        assertThat(service.collectAll().status()).isEqualTo("COMPLETED");
    }

    @Test
    void failureToLoadTargetsReleasesTheGuardForNextExecution() {
        when(store.activeWatchIds()).thenThrow(new DataAccessResourceFailureException("unavailable")).thenReturn(List.of());
        assertThatThrownBy(service::collectAll).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(service.collectAll().status()).isEqualTo("COMPLETED");
    }

    @Test
    void overlapGuardRejectsAnotherRunAndIsReleasedAfterFailure() {
        var date = LocalDate.of(2026, 10, 24);
        var plan = new CollectionStore.Plan(1L, 10L, "00000001", date, date, date, null);
        when(store.activeWatchIds()).thenReturn(List.of(1L));
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures("00000001", date, date)).thenAnswer(invocation -> {
            assertThatThrownBy(service::collectAll).isInstanceOf(ApiException.class)
                    .hasMessageContaining("진행 중");
            throw new OpenDartException(OpenDartException.Kind.TIMEOUT);
        });
        assertThat(service.collectAll().status()).isEqualTo("FAILED");
        when(store.activeWatchIds()).thenReturn(List.of());
        assertThat(service.collectAll().status()).isEqualTo("COMPLETED");
    }
}
