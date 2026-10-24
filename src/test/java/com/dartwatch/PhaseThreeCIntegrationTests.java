package com.dartwatch;

import com.dartwatch.collection.*;
import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.*;
import com.dartwatch.scheduling.SchedulerReadiness;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.dartwatch.support.DartFixtures.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
class PhaseThreeCIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant START = Instant.parse("2026-10-24T03:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 24);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean OpenDartClient client;
    @MockitoBean NotificationSender sender;
    @MockitoBean Clock clock;
    @MockitoSpyBean NotificationDispatchService dispatcher;
    @Autowired CollectionPipelineService pipeline;
    @Autowired SchedulerReadiness readiness;
    @Autowired NotificationProperties properties;
    @Autowired NotificationWorkStore workStore;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired WatchedCompanyRepository watched;
    @Autowired WatchlistService watchlist;
    @Autowired NotificationRuleService rules;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private Long watchId;
    private List<DartDisclosure> source;

    @BeforeEach
    void configurePipeline() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company", "notification_email_settings")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(START);
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        properties.setMaxRetries(1);
        properties.setRetryInterval(Duration.ofMinutes(1));
        properties.setProcessingTimeout(Duration.ofMinutes(10));
        properties.setBatchSize(20);
        workStore.synchronizeConfiguration();
        var company = companies.saveAndFlush(new Company("00000001", "000001", "Test company"));
        watchId = watchlist.register(company.getId()).id();
        rules.putRule(watchId, "A", true);
        rules.setNotifications(watchId, true);
        source = List.of(disclosure("20261024000001", DATE));
        when(client.findDisclosures(eq("00000001"), any(), any())).thenAnswer(invocation -> source);
        when(client.findDisclosures(eq("00000001"), any(), any(), any(DisclosureType.class))).thenAnswer(this::typedSource);
    }

    @AfterEach
    void disable() { properties.setEnabled(false); }

    @Test
    void schedulerCallbacksConnectTheFullPipelineAndDoNotSendInitialOrDuplicateDisclosures() {
        assertThat(readiness.isReady()).isTrue();
        var collectionTick = new CollectionScheduler(pipeline, readiness);
        var deliveryTick = new NotificationScheduler(dispatcher, readiness);
        collectionTick.collect();
        assertThat(disclosures.findAll()).allMatch(Disclosure::isInitialImport);
        assertThat(disclosures.findAll()).extracting(Disclosure::getDisclosureType).containsExactly(DisclosureType.PERIODIC);
        assertThat(deliveries.count()).isZero();
        nextDay();
        collectionTick.collect();
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(disclosures.findByRceptNo("20261025000001").orElseThrow().isInitialImport()).isFalse();
        assertThat(deliveries.findAll()).extracting(NotificationDelivery::getStatus).containsExactly(NotificationStatus.PENDING);
        verifyNoInteractions(sender);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        deliveryTick.dispatch();
        assertThat(deliveries.findAll()).extracting(NotificationDelivery::getStatus).containsExactly(NotificationStatus.SENT);
        collectionTick.collect();
        deliveryTick.dispatch();
        assertThat(deliveries.count()).isEqualTo(1);
        verify(sender, times(1)).send(any());
    }

    @Test
    void collectionFailureHasNoClassificationJobCreationOrEmail() {
        doThrow(new OpenDartException(OpenDartException.Kind.UPSTREAM_SYSTEM)).when(client).findDisclosures(eq("00000001"), any(), any());
        assertThat(pipeline.run().status()).isEqualTo("FAILED");
        assertThat(disclosures.count()).isZero();
        assertThat(deliveries.count()).isZero();
        assertThat(watched.findById(watchId).orElseThrow().getLastSuccessfulCollectionDate()).isNull();
        verify(client, never()).findDisclosures(any(), any(), any(), any(DisclosureType.class));
        verify(dispatcher, never()).enqueue(any());
        new NotificationScheduler(dispatcher, readiness).dispatch();
        verifyNoInteractions(sender);
    }

    @Test
    void classificationFailurePreservesCommittedCollectionAndCanRecoverOnDuplicateCollection() {
        pipeline.run();
        nextDay();
        clearInvocations(dispatcher);
        doThrow(new OpenDartException(OpenDartException.Kind.UPSTREAM_SYSTEM)).when(client)
                .findDisclosures(eq("00000001"), any(), any(), eq(DisclosureType.PERIODIC));
        var failed = pipeline.run();
        assertThat(failed.followUps().getFirst().status()).isEqualTo("CLASSIFICATION_FAILED");
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(watched.findById(watchId).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(DATE.plusDays(1));
        assertThat(disclosures.findByRceptNo("20261025000001").orElseThrow().getDisclosureType()).isNull();
        assertThat(deliveries.count()).isZero();
        verify(dispatcher, never()).enqueue(any());
        doAnswer(this::typedSource).when(client).findDisclosures(eq("00000001"), any(), any(), any(DisclosureType.class));
        assertThat(pipeline.run().collection().companies().getFirst().inserted()).isZero();
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(disclosures.count()).isEqualTo(2);
        verifyNoInteractions(sender);
    }

    @Test
    void jobStoreFailureDoesNotRollbackCollectionOrClassificationAndNextCycleReusesBoth() {
        pipeline.run();
        nextDay();
        doThrow(new DataAccessResourceFailureException("private diagnostic")).when(dispatcher).enqueue(watchId);
        assertThat(pipeline.run().followUps().getFirst().status()).isEqualTo("ENQUEUE_FAILED");
        assertThat(disclosures.findByRceptNo("20261025000001").orElseThrow().getDisclosureType()).isEqualTo(DisclosureType.PERIODIC);
        assertThat(watched.findById(watchId).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(DATE.plusDays(1));
        assertThat(deliveries.count()).isZero();
        doCallRealMethod().when(dispatcher).enqueue(watchId);
        clearInvocations(client);
        pipeline.run();
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(disclosures.count()).isEqualTo(2);
        verify(client, never()).findDisclosures(any(), any(), any(), any(DisclosureType.class));
    }

    @Test
    void mailFailureNeverRollsBackCollectionAndRetryTickDoesNotCallOpenDart() {
        pipeline.run();
        nextDay();
        pipeline.run();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("SMTP_TEMPORARY_REJECTION", true))
                .thenReturn(NotificationSender.SendResult.sent());
        clearInvocations(client);
        var tick = new NotificationScheduler(dispatcher, readiness);
        tick.dispatch();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(watched.findById(watchId).orElseThrow().getLastSuccessfulCollectionDate()).isEqualTo(DATE.plusDays(1));
        tick.dispatch();
        verify(sender, times(1)).send(any());
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(1)));
        tick.dispatch();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(deliveries.findAll().getFirst().getAttemptCount()).isEqualTo(2);
        verify(sender, times(2)).send(any());
        verifyNoInteractions(client);
    }

    @Test
    void disabledMailStillCollectsAndClassifiesWithoutCreatingOrSendingJobs() {
        properties.setEnabled(false);
        workStore.synchronizeConfiguration();
        pipeline.run();
        nextDay();
        pipeline.run();
        assertThat(disclosures.findByRceptNo("20261025000001").orElseThrow().getDisclosureType()).isEqualTo(DisclosureType.PERIODIC);
        assertThat(deliveries.count()).isZero();
        verify(dispatcher, never()).enqueue(any());
        new NotificationScheduler(dispatcher, readiness).dispatch();
        verifyNoInteractions(sender);
    }

    @Test
    void manualCollectionEndpointRemainsCollectionOnlyEvenWhenNotificationsAreEnabled() throws Exception {
        pipeline.run();
        nextDay();
        clearInvocations(client, dispatcher);
        mvc.perform(post("/api/collections")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(disclosures.findByRceptNo("20261025000001").orElseThrow().getDisclosureType()).isNull();
        assertThat(deliveries.count()).isZero();
        verify(client, never()).findDisclosures(any(), any(), any(), any(DisclosureType.class));
        verify(dispatcher, never()).enqueue(any());
        verifyNoInteractions(sender);
    }

    @Test
    void processingSchedulerRecoversStaleClaimsWithoutResendingAnUncertainEmail() {
        pipeline.run();
        nextDay();
        pipeline.run();
        assertThat(workStore.claimOne()).isNotNull();
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(11)));
        new NotificationScheduler(dispatcher, readiness).dispatch();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        assertThat(deliveries.findAll().getFirst().getAttemptCount()).isEqualTo(1);
        verifyNoInteractions(sender);
    }

    private void nextDay() {
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        source = List.of(disclosure("20261024000001", DATE), disclosure("20261025000001", DATE.plusDays(1)));
    }

    private List<DartDisclosure> typedSource(InvocationOnMock invocation) {
        if (invocation.getArgument(3) != DisclosureType.PERIODIC) return List.of();
        LocalDate from = invocation.getArgument(1), through = invocation.getArgument(2);
        return source.stream().filter(record -> !record.parsedReceiptDate().isBefore(from) && !record.parsedReceiptDate().isAfter(through)).toList();
    }
}
