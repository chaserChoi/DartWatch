package com.dartwatch;

import com.dartwatch.collection.*;
import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.opendart.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.dartwatch.support.DartFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@Tag("postgres")
@SpringBootTest
class PhaseFourAIntegrationTests {
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
    @Autowired WatchlistService watchlist;
    @Autowired WatchedCompanyRepository watched;
    @Autowired DisclosureRepository disclosures;
    @Autowired DisclosureCollectionService collector;
    @Autowired CollectionStore store;

    @BeforeEach
    void resetOwnedDataAndClock() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(NOW);
    }

    @Test
    void failedMiddlePageStoresNothingAndNextRunReplaysTheWholeRange() {
        long id = watch("00000001");
        var builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api");
        var server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenDartProperties();
        properties.setApiKey("placeholder");
        properties.setPageSize(2);
        properties.setMaxRetries(0);
        var httpClient = new OpenDartClient(builder.build(), properties, new CorpCodeParser(), new ObjectMapper());
        var service = new DisclosureCollectionService(httpClient, store);
        String firstPage = page(1, 2, 3, 2, item("20261024000001", "20261024") + ","
                + item("20261024000002", "20261024"));
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(service.collectAll().status()).isEqualTo("FAILED");
        assertThat(disclosures.count()).isZero();
        assertThat(watched.findById(id).orElseThrow().getLastSuccessfulCollectionDate()).isNull();

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(1)));
        server.verify();
        server.reset();
        server.expect(queryParam("page_no", "1")).andExpect(queryParam("end_de", "20261024"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2")).andRespond(withSuccess(page(2, 2, 3, 2,
                item("20261024000003", "20261024")), MediaType.APPLICATION_JSON));
        var recovered = new DisclosureCollectionService(httpClient, store).collectAll();
        assertThat(recovered.companies().getFirst().inserted()).isEqualTo(3);
        assertThat(disclosures.findAll()).allMatch(Disclosure::isInitialImport);
        assertThat(watched.findById(id).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(TODAY);
        server.verify();
    }

    @Test
    void databaseErrorAfterFirstInsertRollsBackBatchAndCheckpointThenRecovers() {
        long id = watch("00000001");
        var first = disclosure("20261024000001", TODAY);
        var second = new DartDisclosure("00000001", "Company", "20261024000002", "reject_batch", "20261024", null, null);
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(first, second));
        // Fault injection is confined to the owned, temporary schema.
        jdbc.execute("ALTER TABLE disclosure ADD CONSTRAINT test_reject_batch CHECK (report_name <> 'reject_batch')");
        try {
            assertThat(collector.collectAll().companies().getFirst().error()).isEqualTo("PERSISTENCE");
            assertThat(disclosures.count()).isZero();
            var failed = watched.findById(id).orElseThrow();
            assertThat(failed.getLastSuccessfulCollectionDate()).isNull();
            assertThat(failed.getLastCollectionSucceededAt()).isNull();
            assertThat(failed.getLastCollectionAttemptAt()).isEqualTo(NOW);
        } finally {
            jdbc.execute("ALTER TABLE disclosure DROP CONSTRAINT test_reject_batch");
        }
        var restarted = new DisclosureCollectionService(client, store);
        assertThat(restarted.collectAll().companies().getFirst().inserted()).isEqualTo(2);
        assertThat(restarted.collectAll().companies().getFirst().inserted()).isZero();
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(watched.findById(id).orElseThrow().getLastCollectionError()).isNull();
    }

    @Test
    void interruptionAfterPrepareRestoresInitialBaselineFromDatabase() {
        long id = watch("00000001");
        var interruptedPlan = store.prepare(id);
        // No complete/fail call: same durable state as termination during remote I/O.
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(5)));
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(disclosure("20261024000001", TODAY)));
        var recovered = new DisclosureCollectionService(client, store).collectAll().companies().getFirst();
        assertThat(recovered.from()).isEqualTo(interruptedPlan.from());
        assertThat(recovered.through()).isEqualTo(interruptedPlan.through());
        assertThat(disclosures.findAll()).allMatch(Disclosure::isInitialImport);

        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of());
        var incremental = new DisclosureCollectionService(client, store).collectAll().companies().getFirst();
        assertThat(incremental.from()).isEqualTo(TODAY.minusDays(3));
        assertThat(incremental.through()).isEqualTo(TODAY.plusDays(5));
        assertThat(watched.findById(id).orElseThrow().getInitialSyncThrough()).isEqualTo(TODAY);
    }

    @Test
    void partialFailureKeepsEachCompanyCheckpointAndRetriesOnlyItsUnfinishedRange() {
        long firstId = watch("00000001");
        long secondId = watch("00000002");
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of());
        assertThat(collector.collectAll().status()).isEqualTo("COMPLETED");
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(5)));
        when(client.findDisclosures(eq("00000001"), any(), any())).thenThrow(new OpenDartException(OpenDartException.Kind.TIMEOUT));
        when(client.findDisclosures(eq("00000002"), any(), any())).thenReturn(List.of());
        assertThat(collector.collectAll().status()).isEqualTo("PARTIAL_FAILED");
        assertThat(watched.findById(firstId).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(TODAY);
        assertThat(watched.findById(secondId).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(TODAY.plusDays(5));
        assertThat(watched.findById(firstId).orElseThrow().getLastCollectionSucceededAt()).isEqualTo(NOW);
        assertThat(watched.findById(secondId).orElseThrow().getLastCollectionSucceededAt()).isEqualTo(NOW.plus(Duration.ofDays(5)));

        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(8)));
        doReturn(List.of()).when(client).findDisclosures(any(), any(), any());
        var recovered = new DisclosureCollectionService(client, store).collectAll();
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.companies()).filteredOn(row -> row.watchId().equals(firstId))
                .extracting(CollectionStore.Outcome::from).containsExactly(TODAY.minusDays(3));
        assertThat(recovered.companies()).filteredOn(row -> row.watchId().equals(secondId))
                .extracting(CollectionStore.Outcome::from).containsExactly(TODAY.plusDays(2));
    }

    @Test
    void interruptedIncrementalCollectionReusesDurableSuccessAndDoesNotOverwriteStoredData() {
        long id = watch("00000001");
        var record = disclosure("20261024000001", TODAY);
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(record));
        collector.collectAll();
        var original = disclosures.findByRceptNo(record.rceptNo()).orElseThrow();
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(2)));
        store.prepare(id);
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(10)));
        var changed = new DartDisclosure("00000001", "Changed", record.rceptNo(), "Changed report", "20261024", null, null);
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(changed));
        var result = new DisclosureCollectionService(client, store).collectAll().companies().getFirst();
        assertThat(result.from()).isEqualTo(TODAY.minusDays(3));
        assertThat(result.through()).isEqualTo(TODAY.plusDays(10));
        assertThat(result.inserted()).isZero();
        var unchanged = disclosures.findByRceptNo(record.rceptNo()).orElseThrow();
        assertThat(unchanged.getReportName()).isEqualTo(original.getReportName());
        assertThat(unchanged.getCollectedAt()).isEqualTo(original.getCollectedAt());
        assertThat(unchanged.isInitialImport()).isTrue();
    }

    private long watch(String corpCode) {
        return watchlist.register(companies.saveAndFlush(new Company(corpCode, null, "Company")).getId()).id();
    }
}
