package com.dartwatch;

import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

@Tag("postgres")
@SpringBootTest
class PhaseFourBIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant START = Instant.parse("2026-10-24T03:00:00Z");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean NotificationSender sender;
    @MockitoBean OpenDartClient client;
    @MockitoBean Clock clock;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired NotificationProperties properties;
    @Autowired NotificationDispatchService dispatcher;
    @Autowired NotificationWorkStore store;
    @Autowired NotificationRuleService rules;
    @Autowired WatchlistService watchlist;
    @Autowired WatchedCompanyRepository watched;
    private long watchId;
    private Company company;

    @BeforeEach
    void configureMockDelivery() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company", "notification_email_settings")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        at(START);
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        properties.setMaxRetries(3);
        properties.setRetryInterval(Duration.ofMinutes(10));
        properties.setProcessingTimeout(Duration.ofMinutes(10));
        properties.setBatchSize(20);
        store.synchronizeConfiguration();
        company = companies.saveAndFlush(new Company("00000001", "000001", "테스트 기업"));
        watchId = watchlist.register(company.getId()).id();
        rules.putRule(watchId, "A", true);
        rules.setNotifications(watchId, true);
    }

    @AfterEach
    void resetEnabledFlagAndCheckExternalIsolation() {
        properties.setEnabled(false);
        verifyNoInteractions(client);
    }

    @Test
    void pendingJobSurvivesRestartWithoutResettingActivationOrCreatingADuplicate() {
        save("20261024000001", START.plusSeconds(1), DisclosureType.PERIODIC, false);
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
        var pending = delivery();
        at(START.plus(Duration.ofHours(1)));
        var restarted = restart(store);
        assertThat(jdbc.queryForObject("SELECT enabled_since FROM notification_email_settings", Timestamp.class).toInstant()).isEqualTo(START);
        assertThat(restarted.enqueue(watchId)).isZero();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        assertThat(restarted.dispatch().sent()).isEqualTo(1);
        assertThat(delivery().getId()).isEqualTo(pending.getId());
        assertThat(delivery().getAttemptCount()).isEqualTo(1);
        assertThat(restart(store).dispatch().sent()).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    void restartDoesNotReclaimProcessingBeforeTimeoutAndOnlyQuarantinesItAfterwards() {
        queueOne();
        var claim = store.claimOne();
        at(START.plus(Duration.ofMinutes(9)));
        assertThat(restart(store).dispatch().recoveredUnknown()).isZero();
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        at(START.plus(Duration.ofMinutes(10)));
        assertThat(restart(store).dispatch().recoveredUnknown()).isEqualTo(1);
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        assertThat(delivery().getLastError()).isEqualTo("RECOVERED_IN_FLIGHT");
        assertThat(delivery().getAttemptCount()).isEqualTo(1);
        assertThat(store.finish(claim, NotificationSender.SendResult.sent())).isFalse();
        assertThat(dispatcher.enqueue(watchId)).isZero();
        verifyNoInteractions(sender);
    }

    @Test
    void acceptedMailWithDatabaseWriteFailureIsQuarantinedAndNeverResent() {
        queueOne();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        jdbc.execute("ALTER TABLE notification_delivery ADD CONSTRAINT test_reject_sent CHECK (status <> 'SENT')");
        try {
            assertThat(dispatcher.dispatch().unknown()).isEqualTo(1);
            assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
            assertThat(delivery().getLastError()).isEqualTo("TRANSPORT_UNCERTAIN");
            assertThat(delivery().getSentAt()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE notification_delivery DROP CONSTRAINT test_reject_sent");
        }
        at(START.plus(Duration.ofDays(1)));
        assertThat(restart(store).dispatch().sent()).isZero();
        assertThat(dispatcher.enqueue(watchId)).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    void failureOfBothOutcomeWritesLeavesProcessingUntilSafeUnknownRecovery() {
        queueOne();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        jdbc.execute("ALTER TABLE notification_delivery ADD CONSTRAINT test_reject_outcomes CHECK (status NOT IN ('SENT','UNKNOWN'))");
        try {
            assertThat(dispatcher.dispatch().unknown()).isEqualTo(1);
            assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
            assertThat(delivery().getAttemptCount()).isEqualTo(1);
            assertThat(dispatcher.dispatch().sent()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notification_delivery DROP CONSTRAINT test_reject_outcomes");
        }
        at(START.plus(Duration.ofMinutes(11)));
        assertThat(restart(store).dispatch().recoveredUnknown()).isEqualTo(1);
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verify(sender, times(1)).send(any());
    }

    @Test
    void lostDatabaseAcknowledgmentAfterCommittedSentCannotOverwriteOrResendSent() {
        queueOne();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        var lostAckStore = mock(NotificationWorkStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(store.finish(invocation.getArgument(0), invocation.getArgument(1))).isTrue();
            throw new DataAccessResourceFailureException("lost commit acknowledgment");
        }).when(lostAckStore).finish(any(), eq(NotificationSender.SendResult.sent()));
        assertThat(restart(lostAckStore).dispatch().unknown()).isEqualTo(1);
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivery().getSentAt()).isEqualTo(START);
        at(START.plus(Duration.ofDays(1)));
        assertThat(restart(store).dispatch().sent()).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    void lostClaimAcknowledgmentNeverSendsAnUnconfirmedClaim() {
        queueOne();
        var lostAckStore = mock(NotificationWorkStore.class, delegatesTo(store));
        doAnswer(invocation -> {
            assertThat(store.claimOne()).isNotNull();
            throw new DataAccessResourceFailureException("lost claim acknowledgment");
        }).when(lostAckStore).claimOne();
        assertThatThrownBy(() -> restart(lostAckStore).dispatch()).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        at(START.plus(Duration.ofMinutes(11)));
        assertThat(restart(store).dispatch().recoveredUnknown()).isEqualTo(1);
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verifyNoInteractions(sender);
    }

    @Test
    void concurrentDispatchersDoNotSendTheSameJobWhileSmtpIsBlocked() throws Exception {
        queueOne();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(sender.send(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test SMTP wait expired");
            return NotificationSender.SendResult.sent();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(dispatcher::dispatch);
            try {
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(restart(store)::dispatch);
                assertThat(second.get(3, TimeUnit.SECONDS).sent()).isZero();
                assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
                verify(sender, times(1)).send(any());
            } finally { release.countDown(); }
            assertThat(first.get(3, TimeUnit.SECONDS).sent()).isEqualTo(1);
        }
        assertThat(delivery().getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivery().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void concurrentGenerationWithSeveralRulesProducesOneLogicalJob() throws Exception {
        rules.putRule(watchId, "B", true);
        rules.putRule(watchId, "C", true);
        save("20261024000001", START.plusSeconds(1), DisclosureType.PERIODIC, false);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> enqueue = () -> { start.await(3, TimeUnit.SECONDS); return dispatcher.enqueue(watchId); };
            var first = executor.submit(enqueue);
            var second = executor.submit(enqueue);
            start.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS) + second.get(3, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(deliveries.count()).isEqualTo(1);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        verifyNoInteractions(sender);
    }

    @Test
    void delayedClassificationUsesOriginalDetectionAndNeverCreatesDuplicateOrHistoricalJobs() {
        rules.putRule(watchId, "B", true);
        var delayed = save("20261024000001", START.plusSeconds(1), null, false);
        save("20261024000002", START.minusSeconds(1), DisclosureType.PERIODIC, false);
        save("20261024000003", START.plusSeconds(1), DisclosureType.PERIODIC, true);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        at(START.plus(Duration.ofHours(2)));
        jdbc.update("UPDATE disclosure SET disclosure_type='PERIODIC' WHERE id=?", delayed.getId());
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        assertThat(dispatcher.dispatch().sent()).isEqualTo(1);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    void reactivationDoesNotBackfillDisabledDetectionsEvenIfTheirClassificationArrivesLater() {
        at(START.plusSeconds(1));
        rules.setNotifications(watchId, false);
        var delayed = save("20261024000001", START.plusSeconds(2), null, false);
        at(START.plusSeconds(3));
        rules.setNotifications(watchId, true);
        jdbc.update("UPDATE disclosure SET disclosure_type='PERIODIC' WHERE id=?", delayed.getId());
        assertThat(dispatcher.enqueue(watchId)).isZero();
        save("20261024000002", START.plusSeconds(4), DisclosureType.PERIODIC, false);
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        assertThat(dispatcher.dispatch().sent()).isEqualTo(1);
        verify(sender, times(1)).send(argThat(message -> message.rceptNo().equals("20261024000002")));
    }

    @Test
    void retrySurvivesRestartAndWaitsUntilDueBeforeSucceedingOnce() {
        queueOne();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("CONNECTION_FAILED", true))
                .thenReturn(NotificationSender.SendResult.sent());
        assertThat(dispatcher.dispatch().failed()).isEqualTo(1);
        assertThat(delivery().getLastError()).isEqualTo("CONNECTION_FAILED");
        at(START.plus(Duration.ofMinutes(9)));
        assertThat(restart(store).dispatch().sent()).isZero();
        at(START.plus(Duration.ofMinutes(10)));
        assertThat(restart(store).dispatch().sent()).isEqualTo(1);
        assertThat(delivery().getAttemptCount()).isEqualTo(2);
        assertThat(delivery().getLastAttemptAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(delivery().getSentAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(delivery().getNextAttemptAt()).isNull();
        assertThat(restart(store).dispatch().sent()).isZero();
        verify(sender, times(2)).send(any());
    }

    @Test
    void reducingRetryLimitRetiresExistingRetryQueueWithoutLosingFailureHistory() {
        queueOne();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("SMTP_TEMPORARY_REJECTION", true));
        dispatcher.dispatch();
        properties.setMaxRetries(0);
        at(START.plus(Duration.ofHours(1)));
        assertThat(restart(store).dispatch().failed()).isZero();
        var exhausted = delivery();
        assertThat(exhausted.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(exhausted.isRetryable()).isFalse();
        assertThat(exhausted.getNextAttemptAt()).isNull();
        assertThat(exhausted.getLastError()).isEqualTo("SMTP_TEMPORARY_REJECTION");
        assertThat(exhausted.getLastAttemptAt()).isEqualTo(START);
        properties.setMaxRetries(3);
        assertThat(restart(store).dispatch().failed()).isZero();
        verify(sender, times(1)).send(any());
    }

    private void queueOne() {
        save("20261024000001", START.plusSeconds(1), DisclosureType.PERIODIC, false);
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
    }

    private NotificationDelivery delivery() { return deliveries.findAll().getFirst(); }

    private NotificationDispatchService restart(NotificationWorkStore workStore) {
        var service = new NotificationDispatchService(properties, workStore, sender, watched);
        service.initialize();
        return service;
    }

    private void at(Instant instant) { when(clock.instant()).thenReturn(instant); }

    private Disclosure save(String receipt, Instant detectedAt, DisclosureType type, boolean initial) {
        var disclosure = disclosures.saveAndFlush(new Disclosure(company, receipt, "한글 공시", LocalDate.of(2026, 10, 24), type, initial));
        jdbc.update("UPDATE disclosure SET collected_at=? WHERE id=?", Timestamp.from(detectedAt), disclosure.getId());
        return disclosure;
    }
}
