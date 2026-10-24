package com.dartwatch.disclosure;

import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.dartwatch.support.DartFixtures.*;

@ExtendWith(MockitoExtension.class)
class DisclosureClassificationServiceTests {
    @Mock OpenDartClient client;
    @Mock ClassificationStore store;
    @InjectMocks DisclosureClassificationService service;
    private final LocalDate date = LocalDate.of(2026, 10, 24);

    @ParameterizedTest
    @EnumSource(value = DisclosureType.class, names = {"PERIODIC", "MATERIAL_EVENT", "ISSUANCE", "SHAREHOLDING", "OTHER"})
    void mapsReceiptsToEachOfficialMvpTypeAndStopsWhenAllTargetsAreFound(DisclosureType type) {
        var plan = plan(1);
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures(eq("00000001"), eq(date), eq(date), any(DisclosureType.class))).thenReturn(List.of());
        when(client.findDisclosures("00000001", date, date, type))
                .thenReturn(List.of(disclosure("20261024000001", date), disclosure("20261024000099", date)));
        when(store.apply(eq(plan), anyMap())).thenReturn(1);
        var result = service.classify(1L);
        assertThat(result.classified()).isEqualTo(1);
        verify(store).apply(plan, Map.of("20261024000001", type));
        assertThat(result.queriedTypes()).isEqualTo(type.ordinal() + 1);
        verify(client, never()).findDisclosures(any(), any(), any(), eq(DisclosureType.EXTERNAL_AUDIT));
    }

    @Test
    void unknownResultsAreNotInferredAsOther() {
        var plan = plan(1);
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures(any(), any(), any(), any(DisclosureType.class))).thenReturn(List.of());
        when(store.apply(eq(plan), anyMap())).thenReturn(1);
        var result = service.classify(1L);
        assertThat(result.unclassified()).isEqualTo(1);
        assertThat(result.queriedTypes()).isEqualTo(5);
        verify(store).apply(plan, Map.of());
        verify(client, times(5)).findDisclosures(any(), any(), any(), any(DisclosureType.class));
    }

    @Test
    void cachedOrAbsentTargetsDoNotCauseAnyApiCalls() {
        when(store.prepare(1L)).thenReturn(null);
        assertThat(service.classify(1L).queriedTypes()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void failedTypeLookupNeverCommitsPartialClassification() {
        var plan = plan(2);
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures("00000001", date, date, DisclosureType.PERIODIC))
                .thenReturn(List.of(disclosure("20261024000001", date)));
        when(client.findDisclosures("00000001", date, date, DisclosureType.MATERIAL_EVENT))
                .thenThrow(new OpenDartException(OpenDartException.Kind.RATE_LIMIT));
        assertThatThrownBy(() -> service.classify(1L)).isInstanceOf(OpenDartException.class);
        verify(store, never()).apply(any(), anyMap());
        verify(client, never()).findDisclosures(any(), any(), any(), eq(DisclosureType.ISSUANCE));
    }

    @Test
    void conflictingTypesForSameReceiptAreRejected() {
        when(store.prepare(1L)).thenReturn(plan(2));
        when(client.findDisclosures(any(), any(), any(), any(DisclosureType.class)))
                .thenReturn(List.of(disclosure("20261024000001", date)));
        assertThatThrownBy(() -> service.classify(1L)).isInstanceOf(OpenDartException.class);
        verify(store, never()).apply(any(), anyMap());
    }

    private ClassificationStore.Plan plan(int count) {
        var targets = count == 1 ? List.of(new ClassificationStore.Target(10L, "20261024000001", date))
                : List.of(new ClassificationStore.Target(10L, "20261024000001", date),
                        new ClassificationStore.Target(11L, "20261024000002", date));
        return new ClassificationStore.Plan(1L, "00000001", date, date, targets);
    }
}
