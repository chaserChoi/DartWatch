package com.dartwatch.company;

import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.CorpRecord;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanySyncServiceTests {
    @Mock OpenDartClient client;
    @Mock CompanyDirectoryStore store;
    @InjectMocks CompanySyncService service;

    @Test
    void completedDirectoryIsNotDownloadedAgain() {
        when(store.isSynchronized()).thenReturn(true);
        assertThat(service.synchronize(false).downloaded()).isFalse();
        verifyNoInteractions(client);
    }

    @Test
    void explicitRefreshDownloadsAndUpdatesExistingDirectory() {
        var records = List.of(new CorpRecord("00000001", "Updated", null, LocalDate.of(2026, 10, 24)));
        when(client.downloadCompanies()).thenReturn(records);
        when(store.apply(records)).thenReturn(1);
        assertThat(service.synchronize(true).companyCount()).isEqualTo(1);
        verify(store).apply(records);
    }
}
