package com.dartwatch;

import com.dartwatch.api.ApiException;
import com.dartwatch.company.Company;
import com.dartwatch.company.CompanyRepository;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static com.dartwatch.support.DartFixtures.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
class PhaseThreeAIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant START = Instant.parse("2026-10-24T03:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 24);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean OpenDartClient client;
    @MockitoBean Clock clock;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationRuleRepository rules;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired WatchedCompanyRepository watched;
    @Autowired WatchlistService watchlist;
    @Autowired NotificationRuleService settings;
    @Autowired NotificationFilterService filter;
    @Autowired DisclosureClassificationService classification;
    @Autowired ClassificationStore classificationStore;
    @Autowired MockMvc mvc;
    private Long watchId;
    private Company company;

    @BeforeEach
    void setUp() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company")) {
            jdbc.update("DELETE FROM " + table);
        }
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(START);
        company = companies.saveAndFlush(new Company("00000001", "000001", "Test company"));
        watchId = watchlist.register(company.getId()).id();
    }

    @Test
    void rulesApiUpsertsMvpTypesValidatesInputsAndDefaultsToDisabled() throws Exception {
        mvc.perform(get("/api/watchlist/{id}/notification-rules", watchId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificationsEnabled").value(false))
                .andExpect(jsonPath("$.rules").isEmpty());
        mvc.perform(put("/api/watchlist/{id}/notification-rules/A", watchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules[0].type").value("A"));
        settings.putRule(watchId, "A", true);
        assertThat(rules.count()).isEqualTo(1);
        assertThat(rules.findAll().getFirst().getEnabledSince()).isEqualTo(START);
        for (String unsupported : List.of("F", "G", "H", "I", "J", "PERIODIC", "a")) {
            mvc.perform(put("/api/watchlist/{id}/notification-rules/{type}", watchId, unsupported)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(put("/api/watchlist/{id}/notification-rules/A", watchId)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/watchlist/999999/notification-rules")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/watchlist/{id}/notifications", watchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.notificationsEnabled").value(true));
        mvc.perform(put("/api/watchlist/{id}/notification-rules/A", watchId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules[0].enabled").value(false));
        assertThat(deliveries.count()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void classifiesAllFiveTypesByReceiptPreservesCorrectionsAndCachesUnknowns() throws Exception {
        var types = Arrays.stream(DisclosureType.values()).filter(DisclosureType::isMvpNotificationType).toList();
        for (int index = 0; index < types.size(); index++) {
            var type = types.get(index);
            String receipt = "2026102400000" + (index + 1);
            save(receipt, null, false, START.plusSeconds(1));
            when(client.findDisclosures("00000001", DATE, DATE, type)).thenReturn(List.of(disclosure(receipt, DATE)));
        }
        save("20261024000006", null, false, START.plusSeconds(1));
        save("20261024000007", null, false, START.plusSeconds(1)); // Not found in A..E: potentially F..J, still unknown.
        when(client.findDisclosures("00000001", DATE, DATE, DisclosureType.MATERIAL_EVENT))
                .thenReturn(List.of(disclosure("20261024000002", DATE), disclosure("20261024000006", DATE),
                        disclosure("20261024000099", DATE)));
        mvc.perform(post("/api/watchlist/{id}/disclosure-classifications", watchId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.classified").value(6))
                .andExpect(jsonPath("$.unclassified").value(1));
        for (int index = 0; index < types.size(); index++) {
            assertThat(disclosures.findByRceptNo("2026102400000" + (index + 1)).orElseThrow().getDisclosureType())
                    .isEqualTo(types.get(index));
        }
        assertThat(disclosures.findByRceptNo("20261024000006").orElseThrow().getDisclosureType())
                .isEqualTo(DisclosureType.MATERIAL_EVENT);
        var unknown = disclosures.findByRceptNo("20261024000007").orElseThrow();
        assertThat(unknown.getDisclosureType()).isNull();
        assertThat(unknown.getClassificationCheckedAt()).isEqualTo(START);
        assertThat(disclosures.count()).isEqualTo(7); // Type lookups never insert extra rows.
        assertThat(classification.classify(watchId).queriedTypes()).isZero();
        verify(client, times(5)).findDisclosures(any(), any(), any(), any(DisclosureType.class));
        verify(client, never()).findDisclosures(any(), any(), any(), eq(DisclosureType.EXTERNAL_AUDIT));
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void candidatesExcludeHistoricInitialWrongTypeAndUnknownButKeepDelayedClassification() throws Exception {
        enableA();
        save("20261024000001", null, false, START.minusSeconds(1)); // Phase 2 / pre-activation data.
        save("20261024000002", null, true, START.plusSeconds(1));
        save("20261024000003", null, false, START.plusSeconds(2));
        save("20261024000004", null, false, START.plusSeconds(3));
        save("20261024000005", DisclosureType.OTHER, false, START.plusSeconds(4));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        atSeconds(100);
        when(client.findDisclosures(any(), any(), any(), any(DisclosureType.class))).thenReturn(List.of());
        when(client.findDisclosures("00000001", DATE, DATE, DisclosureType.PERIODIC)).thenReturn(List.of(
                disclosure("20261024000001", DATE), disclosure("20261024000002", DATE), disclosure("20261024000003", DATE)));
        classification.classify(watchId);
        mvc.perform(get("/api/watchlist/{id}/notification-candidates", watchId).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].rceptNo").value("20261024000003"));
        assertThat(disclosures.findByRceptNo("20261024000003").orElseThrow().getCollectedAt()).isEqualTo(START.plusSeconds(2));
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void noConditionsMeansNoCandidatesAndLaterConditionsDoNotBackfillOldDetections() {
        settings.setNotifications(watchId, true);
        save("20261024000001", DisclosureType.PERIODIC, false, START.plusSeconds(1));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        atSeconds(10);
        settings.putRule(watchId, "A", true);
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        save("20261024000002", DisclosureType.PERIODIC, false, START.plusSeconds(11));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isEqualTo(1);
    }

    @Test
    void notificationReactivationExcludesTheDisabledPeriodAndDoesNotResetOnRepeatedEnable() {
        enableA();
        save("20261024000001", DisclosureType.PERIODIC, false, START.plusSeconds(1));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isEqualTo(1);
        atSeconds(10);
        settings.setNotifications(watchId, false);
        save("20261024000002", DisclosureType.PERIODIC, false, START.plusSeconds(11));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        atSeconds(20);
        settings.setNotifications(watchId, true);
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        save("20261024000003", DisclosureType.PERIODIC, false, START.plusSeconds(21));
        atSeconds(30);
        settings.setNotifications(watchId, true);
        assertThat(filter.preview(watchId, 0, 10).items()).extracting(NotificationFilterService.Candidate::rceptNo)
                .containsExactly("20261024000003");
    }

    @Test
    void ruleAndMonitoringReactivationExcludeTheirDisabledPeriods() {
        enableA();
        save("20261024000001", DisclosureType.PERIODIC, false, START.plusSeconds(1));
        atSeconds(10);
        settings.putRule(watchId, "A", false);
        save("20261024000002", DisclosureType.PERIODIC, false, START.plusSeconds(11));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        atSeconds(20);
        settings.putRule(watchId, "A", true);
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        save("20261024000003", DisclosureType.PERIODIC, false, START.plusSeconds(21));
        atSeconds(30);
        watchlist.setMonitoring(watchId, false);
        save("20261024000004", DisclosureType.PERIODIC, false, START.plusSeconds(31));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        atSeconds(40);
        watchlist.setMonitoring(watchId, true);
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
        save("20261024000005", DisclosureType.PERIODIC, false, START.plusSeconds(41));
        assertThat(filter.preview(watchId, 0, 10).items()).extracting(NotificationFilterService.Candidate::rceptNo)
                .containsExactly("20261024000005");
    }

    @Test
    void candidatesAreScopedToCompanyAndUnsupportedLegacyTypesNeverMatch() {
        enableA();
        var other = companies.saveAndFlush(new Company("00000002", null, "Other company"));
        var otherDisclosure = disclosures.saveAndFlush(new Disclosure(other, "20261024000001", "Other", DATE,
                DisclosureType.PERIODIC, false));
        jdbc.update("UPDATE disclosure SET collected_at = ? WHERE id = ?", Timestamp.from(START.plusSeconds(1)), otherDisclosure.getId());
        rules.saveAndFlush(new NotificationRule(watched.findById(watchId).orElseThrow(), DisclosureType.EXTERNAL_AUDIT,
                NotificationChannel.EMAIL, START));
        save("20261024000002", DisclosureType.EXTERNAL_AUDIT, false, START.plusSeconds(1));
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isZero();
    }

    @Test
    void failedClassificationDoesNotMarkOrPartiallyClassifyRows() {
        save("20261024000001", null, false, START);
        save("20261024000002", null, false, START);
        when(client.findDisclosures("00000001", DATE, DATE, DisclosureType.PERIODIC))
                .thenReturn(List.of(disclosure("20261024000001", DATE)));
        when(client.findDisclosures("00000001", DATE, DATE, DisclosureType.MATERIAL_EVENT))
                .thenThrow(new OpenDartException(OpenDartException.Kind.UPSTREAM_SYSTEM));
        assertThatThrownBy(() -> classification.classify(watchId)).isInstanceOf(OpenDartException.class);
        assertThat(disclosures.findAll()).allSatisfy(disclosure -> {
            assertThat(disclosure.getDisclosureType()).isNull();
            assertThat(disclosure.getClassificationCheckedAt()).isNull();
        });
    }

    @Test
    void unknownCooldownExpiresWithoutLosingAnEligibleDelayedDisclosure() {
        enableA();
        save("20261024000001", null, false, START.plusSeconds(1));
        atSeconds(10);
        when(client.findDisclosures(any(), any(), any(), any(DisclosureType.class))).thenReturn(List.of());
        classification.classify(watchId);
        assertThat(classification.classify(watchId).queriedTypes()).isZero();
        atSeconds(24 * 3600 + 11);
        when(client.findDisclosures("00000001", DATE, DATE, DisclosureType.PERIODIC))
                .thenReturn(List.of(disclosure("20261024000001", DATE)));
        classification.classify(watchId);
        assertThat(filter.preview(watchId, 0, 10).totalElements()).isEqualTo(1);
    }

    @Test
    void dateWindowBoundsBackfillAndDisabledWatchDoesNotCallApi() {
        save("20261024000001", null, false, START);
        disclosures.saveAndFlush(new Disclosure(company, "20261124000001", "Later", DATE.plusDays(31), null, false));
        var plan = classificationStore.prepare(watchId);
        assertThat(plan.targets()).hasSize(1);
        assertThat(plan.from()).isEqualTo(DATE);
        assertThat(plan.through()).isEqualTo(DATE);
        watchlist.setMonitoring(watchId, false);
        assertThatThrownBy(() -> classification.classify(watchId)).isInstanceOf(ApiException.class);
        verifyNoInteractions(client);
    }

    @Test
    void neverCheckedDisclosuresTakePriorityOverExpiredUnknownBacklog() {
        var old = save("20261024000001", null, false, START);
        jdbc.update("UPDATE disclosure SET classification_checked_at = ? WHERE id = ?",
                Timestamp.from(START.minus(Duration.ofDays(2))), old.getId());
        disclosures.saveAndFlush(new Disclosure(company, "20261124000001", "New unchecked", DATE.plusDays(31), null, false));
        var plan = classificationStore.prepare(watchId);
        assertThat(plan.targets()).extracting(ClassificationStore.Target::rceptNo).containsExactly("20261124000001");
        assertThat(plan.from()).isEqualTo(DATE.plusDays(31));
        assertThat(plan.through()).isEqualTo(DATE.plusDays(31));
    }

    @Test
    void severalEnabledConditionsReturnUniquePaginatedCandidatesWithoutCreatingDeliveries() {
        enableA();
        settings.putRule(watchId, "B", true);
        save("20261024000001", DisclosureType.PERIODIC, false, START.plusSeconds(1));
        save("20261024000002", DisclosureType.MATERIAL_EVENT, false, START.plusSeconds(2));
        var first = filter.preview(watchId, 0, 1);
        var second = filter.preview(watchId, 1, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(second.totalElements()).isEqualTo(2);
        assertThat(first.items().getFirst().id()).isNotEqualTo(second.items().getFirst().id());
        assertThat(deliveries.count()).isZero();
    }

    @Test
    void v3MigrationPreservesLegacyRowsAndLeavesNotificationsSafelyDisabled() {
        String legacy = "dw_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).target("2").load().migrate();
            jdbc.update("INSERT INTO " + legacy + ".company (id,corp_code,name,created_at) VALUES (1,'00000001','Legacy',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO " + legacy + ".watched_company (id,company_id,monitoring_enabled,created_at) VALUES (1,1,TRUE,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO " + legacy + ".notification_rule (id,watched_company_id,disclosure_type,channel,enabled,created_at) "
                    + "VALUES (1,1,'PERIODIC','EMAIL',TRUE,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO " + legacy + ".disclosure (id,company_id,rcept_no,report_name,receipt_date,initial_import,collected_at) "
                    + "VALUES (1,1,'20261024000001','Legacy',CURRENT_DATE,FALSE,CURRENT_TIMESTAMP)");
            Flyway.configure().dataSource(dataSource).schemas(legacy).defaultSchema(legacy).load().migrate();
            assertThat(jdbc.queryForObject("SELECT notifications_enabled FROM " + legacy + ".watched_company WHERE id=1", Boolean.class)).isFalse();
            assertThat(jdbc.queryForObject("SELECT enabled FROM " + legacy + ".notification_rule WHERE id=1", Boolean.class)).isTrue();
            assertThat(jdbc.queryForObject("SELECT enabled_since FROM " + legacy + ".notification_rule WHERE id=1", Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + legacy + ".disclosure", Long.class)).isEqualTo(1);
        } finally {
            if (!legacy.matches("dw_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid owned legacy schema");
            jdbc.execute("DROP SCHEMA IF EXISTS \"" + legacy + "\" CASCADE");
        }
    }

    private void enableA() {
        settings.putRule(watchId, "A", true);
        settings.setNotifications(watchId, true);
    }

    private void atSeconds(long seconds) { when(clock.instant()).thenReturn(START.plusSeconds(seconds)); }

    private Disclosure save(String receipt, DisclosureType type, boolean initial, Instant detectedAt) {
        var disclosure = disclosures.saveAndFlush(new Disclosure(company, receipt, "Test report", DATE, type, initial));
        jdbc.update("UPDATE disclosure SET collected_at = ? WHERE id = ?", Timestamp.from(detectedAt), disclosure.getId());
        return disclosure;
    }
}
