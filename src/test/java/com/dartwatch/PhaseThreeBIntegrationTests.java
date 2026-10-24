package com.dartwatch;

import com.dartwatch.api.ApiException;
import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
class PhaseThreeBIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant START = Instant.parse("2026-10-24T03:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 24);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean NotificationSender sender;
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
    @Autowired MockMvc mvc;
    private Long watchId;
    private Company company;

    @BeforeEach
    void configureMockDelivery() throws Exception {
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
    void resetEnabledFlag() { properties.setEnabled(false); }

    @Test
    void explicitJobCreationIsIdempotentAndSmtpAcceptanceIsTerminalAcrossDispatcherRestart() {
        save("20261024000001", START.plusSeconds(1), false);
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        var result = dispatcher.dispatch();
        assertThat(result.sent()).isEqualTo(1);
        var sent = deliveries.findAll().getFirst();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getRecipient()).isEqualTo("recipient@example.invalid");
        assertThat(sent.getAttemptCount()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();
        var restarted = new NotificationDispatchService(properties, store, sender, mock(WatchedCompanyRepository.class));
        restarted.initialize();
        assertThat(restarted.dispatch().sent()).isZero();
        assertThat(dispatcher.enqueue(watchId)).isZero();
        verify(sender, times(1)).send(any());
    }

    @Test
    void pastInitialAndPreGlobalActivationRowsAreNeverEnqueued() {
        save("20261024000001", START.minusSeconds(1), false);
        save("20261024000002", START.plusSeconds(1), true);
        assertThat(dispatcher.enqueue(watchId)).isZero();
        properties.setEnabled(false);
        store.synchronizeConfiguration();
        save("20261024000003", START.plusSeconds(10), false);
        when(clock.instant()).thenReturn(START.plusSeconds(20));
        properties.setEnabled(true);
        store.synchronizeConfiguration();
        assertThat(dispatcher.enqueue(watchId)).isZero();
        save("20261024000004", START.plusSeconds(21), false);
        assertThat(dispatcher.enqueue(watchId)).isEqualTo(1);
        verifyNoInteractions(sender);
    }

    @Test
    void retryHonorsIntervalAndMaximumAttemptsWithoutAnImmediateLoop() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("SMTP_TEMPORARY_REJECTION", true));
        assertThat(dispatcher.dispatch().failed()).isEqualTo(1);
        var first = deliveries.findAll().getFirst();
        assertThat(first.isRetryable()).isTrue();
        assertThat(first.getNextAttemptAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        assertThat(dispatcher.dispatch().failed()).isZero();
        when(clock.instant()).thenReturn(START.plus(Duration.ofMinutes(10)));
        assertThat(dispatcher.dispatch().failed()).isEqualTo(1);
        var exhausted = deliveries.findAll().getFirst();
        assertThat(exhausted.getAttemptCount()).isEqualTo(2);
        assertThat(exhausted.isRetryable()).isFalse();
        assertThat(exhausted.getNextAttemptAt()).isNull();
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        dispatcher.dispatch();
        verify(sender, times(2)).send(any());
    }

    @Test
    void authenticationFailureIsNotRetried() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("AUTHENTICATION", false));
        dispatcher.dispatch();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(deliveries.findAll().getFirst().getLastError()).isEqualTo("AUTHENTICATION");
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        dispatcher.dispatch();
        assertThat(deliveries.findAll().getFirst().isRetryable()).isFalse();
        verify(sender, times(1)).send(any());
    }

    @Test
    void disablingDeliveryPreservesPermanentFailureHistory() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("AUTHENTICATION", false));
        dispatcher.dispatch();
        properties.setEnabled(false);
        store.synchronizeConfiguration();
        var failure = deliveries.findAll().getFirst();
        assertThat(failure.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failure.getLastError()).isEqualTo("AUTHENTICATION");
    }

    @Test
    void ambiguousTransportOutcomeIsNotAutomaticallyRetriedOrReenqueued() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.unknown());
        assertThat(dispatcher.dispatch().unknown()).isEqualTo(1);
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        assertThat(dispatcher.dispatch().unknown()).isZero();
        assertThat(dispatcher.enqueue(watchId)).isZero();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verify(sender, times(1)).send(any());
    }

    @Test
    void staleProcessingBecomesUnknownAndLateResultCannotOverwriteRecovery() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        var claim = store.claimOne();
        assertThat(claim).isNotNull();
        when(clock.instant()).thenReturn(START.plus(Duration.ofMinutes(11)));
        assertThat(store.recoverStale()).isEqualTo(1);
        assertThat(store.finish(claim, NotificationSender.SendResult.sent())).isFalse();
        assertThat(dispatcher.dispatch().sent()).isZero();
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.UNKNOWN);
        verifyNoInteractions(sender);
    }

    @Test
    void simultaneousDatabaseClaimsYieldOnlyOneOwner() throws Exception {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<NotificationWorkStore.Claim> task = () -> { start.await(5, TimeUnit.SECONDS); return store.claimOne(); };
            var first = executor.submit(task);
            var second = executor.submit(task);
            start.countDown();
            var claims = java.util.stream.Stream.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull).toList();
            assertThat(claims).hasSize(1);
            assertThat(deliveries.findAll().getFirst().getAttemptCount()).isEqualTo(1);
        }
    }

    @Test
    void databaseUniquenessIncludesNormalizedRecipientAndChannel() {
        var disclosure = save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        assertThatThrownBy(() -> deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL,
                "Recipient@Example.Invalid"))).isInstanceOf(DataIntegrityViolationException.class);
        deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL, "other@example.invalid"));
        assertThat(deliveries.count()).isEqualTo(2);
    }

    @Test
    void changingRulesAfterEnqueueCancelsJobWithoutSending() {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        rules.setNotifications(watchId, false);
        assertThat(dispatcher.dispatch().cancelled()).isEqualTo(1);
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        verifyNoInteractions(sender);
    }

    @Test
    void disabledConfigurationAndMissingConfirmationCannotSendAndHistoryIsPaginated() throws Exception {
        save("20261024000001", START.plusSeconds(1), false);
        dispatcher.enqueue(watchId);
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":false}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications").param("status", "PENDING").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].recipient").value("recipient@example.invalid"));
        properties.setEnabled(false);
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":true}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOTIFICATIONS_DISABLED"));
        assertThat(deliveries.findAll().getFirst().getStatus()).isEqualTo(NotificationStatus.CANCELLED);
        verifyNoInteractions(sender);
    }

    @Test
    void legacyUnknownRecipientPreventsAccidentalNewRecipientBackfill() {
        var disclosure = save("20261024000001", START.plusSeconds(1), false);
        deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL));
        assertThat(dispatcher.enqueue(watchId)).isZero();
        assertThat(store.claimOne()).isNull();
    }

    @Test
    void v4PreservesSentLegacyRowsAndQuarantinesInFlightWithoutGuessingRecipients() {
        String legacy = "dw_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).target("3").load().migrate();
            jdbc.update("INSERT INTO " + legacy + ".company (id,corp_code,name,created_at) VALUES (1,'00000001','Legacy',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO " + legacy + ".disclosure (id,company_id,rcept_no,report_name,receipt_date,initial_import,collected_at) "
                    + "VALUES (1,1,'20261024000001','Legacy',CURRENT_DATE,FALSE,CURRENT_TIMESTAMP),(2,1,'20261024000002','Legacy',CURRENT_DATE,FALSE,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO " + legacy + ".notification_delivery (id,disclosure_id,channel,status,attempt_count,last_attempt_at,sent_at,created_at,version) "
                    + "VALUES (1,1,'EMAIL','SENT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0),"
                    + "(2,2,'EMAIL','SENDING',1,CURRENT_TIMESTAMP,NULL,CURRENT_TIMESTAMP,0)");
            Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).load().migrate();
            assertThat(jdbc.queryForObject("SELECT status FROM " + legacy + ".notification_delivery WHERE id=1", String.class)).isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT status FROM " + legacy + ".notification_delivery WHERE id=2", String.class)).isEqualTo("UNKNOWN");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + legacy + ".notification_delivery WHERE recipient IS NULL", Integer.class)).isEqualTo(2);
        } finally {
            if (!legacy.matches("dw_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + legacy + "\" CASCADE");
        }
    }

    private Disclosure save(String receipt, Instant detectedAt, boolean initial) {
        var disclosure = disclosures.saveAndFlush(new Disclosure(company, receipt, "한글 공시", DATE, DisclosureType.PERIODIC, initial));
        jdbc.update("UPDATE disclosure SET collected_at=? WHERE id=?", Timestamp.from(detectedAt), disclosure.getId());
        return disclosure;
    }
}
