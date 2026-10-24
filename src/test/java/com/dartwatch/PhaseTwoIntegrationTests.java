package com.dartwatch;

import com.dartwatch.collection.*;
import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.opendart.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.dartwatch.support.DartFixtures.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
class PhaseTwoIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean OpenDartClient client;
    @MockitoBean Clock clock;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyRepository companies;
    @Autowired CompanyDirectoryStore directory;
    @Autowired CompanySyncService sync;
    @Autowired WatchlistService watchlist;
    @Autowired WatchedCompanyRepository watched;
    @Autowired DisclosureRepository disclosures;
    @Autowired DisclosureCollectionService collector;
    @Autowired MockMvc mvc;

    @BeforeEach
    void resetOwnedDataAndClock() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company", "company_directory_sync")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void directorySyncIsIdempotentAndRefreshPreservesCompanyIdentityAndWatch() {
        var original = List.of(new CorpRecord("00000001", "Original", "000001", TODAY.minusDays(1)),
                new CorpRecord("00000002", "Unlisted", null, TODAY));
        when(client.downloadCompanies()).thenReturn(original);
        sync.synchronize(false);
        var company = companies.findByCorpCode("00000001").orElseThrow();
        watchlist.register(company.getId());
        sync.synchronize(false);
        verify(client, times(1)).downloadCompanies();
        when(client.downloadCompanies()).thenReturn(List.of(
                new CorpRecord("00000001", "Updated", "000002", TODAY),
                new CorpRecord("00000002", "Now listed", "000001", TODAY)));
        sync.synchronize(true);
        assertThat(companies.count()).isEqualTo(2);
        var updated = companies.findById(company.getId()).orElseThrow();
        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getStockCode()).isEqualTo("000002");
        assertThat(updated.getDartModifiedOn()).isEqualTo(TODAY);
        assertThat(watchlist.list()).hasSize(1);
    }

    @Test
    void invalidDirectoryBatchRollsBackRowsAndCompletionMarker() {
        var duplicateStockCodes = List.of(new CorpRecord("00000001", "One", "000001", TODAY),
                new CorpRecord("00000002", "Two", "000001", TODAY));
        assertThatThrownBy(() -> directory.apply(duplicateStockCodes)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(companies.count()).isZero();
        assertThat(directory.isSynchronized()).isFalse();
    }

    @Test
    void restSearchAndWatchlistCrudValidateIdentifiersAndConflicts() throws Exception {
        directory.apply(List.of(new CorpRecord("00000001", "테스트 기업", "000001", TODAY)));
        Long companyId = companies.findByCorpCode("00000001").orElseThrow().getId();
        mvc.perform(get("/api/companies").param("query", "테스트"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].corpCode").value("00000001"));
        mvc.perform(get("/api/companies").param("query", "000001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].stockCode").value("000001"));
        mvc.perform(get("/api/companies").param("query", " ")).andExpect(status().isBadRequest());
        String body = "{\"companyId\":" + companyId + "}";
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.monitoringEnabled").value(true));
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_WATCHED"));
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content("{\"companyId\":999999}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        long id = watched.findByCompanyId(companyId).orElseThrow().getId();
        mvc.perform(patch("/api/watchlist/{id}/monitoring", id).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.monitoringEnabled").value(false));
        mvc.perform(get("/api/watchlist")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].company.name").value("테스트 기업"));
        mvc.perform(delete("/api/watchlist/{id}", id)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/watchlist/{id}", id)).andExpect(status().isNotFound());
        assertThat(companies.count()).isEqualTo(1);
        verifyNoInteractions(client);
    }

    @Test
    void initialRepeatedAndIncrementalCollectionAreIdempotentAndOnlyMonitorActiveWatches() {
        Long id = activeWatch();
        var other = companies.saveAndFlush(new Company("00000002", null, "Disabled"));
        var disabled = watchlist.register(other.getId());
        watchlist.setMonitoring(disabled.id(), false);
        var initial = disclosure("20261023000001", TODAY.minusDays(1));
        var sameDay = disclosure("20261024000001", TODAY);
        when(client.findDisclosures(eq("00000001"), any(), any())).thenReturn(List.of(initial, sameDay));
        assertThat(collector.collectAll().companies().getFirst().inserted()).isEqualTo(2);
        assertThat(collector.collectAll().companies().getFirst().inserted()).isZero();
        assertThat(disclosures.findAll()).allMatch(Disclosure::isInitialImport);

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(1)));
        var lateHistorical = disclosure("20261024000002", TODAY);
        var fresh = disclosure("20261025000001", TODAY.plusDays(1));
        when(client.findDisclosures(eq("00000001"), any(), any())).thenReturn(List.of(initial, sameDay, lateHistorical, fresh));
        var result = collector.collectAll().companies().getFirst();
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.from()).isEqualTo(TODAY.minusDays(3));
        assertThat(disclosures.count()).isEqualTo(4);
        assertThat(disclosures.findByRceptNo(lateHistorical.rceptNo()).orElseThrow().isInitialImport()).isTrue();
        var storedFresh = disclosures.findByRceptNo(fresh.rceptNo()).orElseThrow();
        assertThat(storedFresh.isInitialImport()).isFalse();
        assertThat(storedFresh.getDisclosureType()).isNull();
        assertThat(storedFresh.getFilerName()).isEqualTo("Test filer");
        assertThat(storedFresh.getCompanyName()).isEqualTo("테스트 기업");
        assertThat(watched.findById(id).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(TODAY.plusDays(1));
        verify(client, never()).findDisclosures(eq("00000002"), any(), any());
    }

    @Test
    void failedInitialQueryRetriesSameBaselineAndDoesNotAdvanceSuccessCursor() {
        Long id = activeWatch();
        when(client.findDisclosures(any(), any(), any())).thenThrow(new OpenDartException(OpenDartException.Kind.UPSTREAM_SYSTEM));
        assertThat(collector.collectAll().status()).isEqualTo("FAILED");
        var failed = watched.findById(id).orElseThrow();
        assertThat(failed.getInitialSyncThrough()).isEqualTo(TODAY);
        assertThat(failed.getLastSuccessfulCollectionDate()).isNull();
        assertThat(failed.getLastCollectionError()).isEqualTo("UPSTREAM_SYSTEM");
        assertThat(disclosures.count()).isZero();

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(1)));
        doReturn(List.of(disclosure("20261024000001", TODAY))).when(client).findDisclosures(any(), any(), any());
        var retried = collector.collectAll().companies().getFirst();
        assertThat(retried.from()).isEqualTo(TODAY.minusDays(29));
        assertThat(retried.through()).isEqualTo(TODAY);
        assertThat(disclosures.findAll()).allMatch(Disclosure::isInitialImport);
        when(client.findDisclosures(any(), any(), any())).thenThrow(new OpenDartException(OpenDartException.Kind.TIMEOUT));
        collector.collectAll();
        var afterFailure = watched.findById(id).orElseThrow();
        assertThat(afterFailure.getLastSuccessfulCollectionDate()).isEqualTo(TODAY);
        assertThat(afterFailure.getLastCollectionError()).isEqualTo("TIMEOUT");
    }

    @Test
    void persistenceFailureRollsBackEntireCompanyBatchAndCursor() {
        Long id = activeWatch();
        var valid = disclosure("20261024000001", TODAY);
        var invalid = new DartDisclosure("00000001", "Test", "20261024000002", " ", "20261024", null, null);
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(valid, invalid));
        assertThat(collector.collectAll().status()).isEqualTo("FAILED");
        assertThat(disclosures.count()).isZero();
        assertThat(watched.findById(id).orElseThrow().getLastSuccessfulCollectionDate()).isNull();
    }

    @Test
    void emptyCollectionIsSuccessfulAndManualApiUsesTheSameCollector() throws Exception {
        Long id = activeWatch();
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of());
        mvc.perform(post("/api/collections")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.companies[0].fetched").value(0));
        assertThat(watched.findById(id).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(TODAY);
    }

    private Long activeWatch() {
        var company = companies.saveAndFlush(new Company("00000001", "000001", "테스트 기업"));
        return watchlist.register(company.getId()).id();
    }
}
