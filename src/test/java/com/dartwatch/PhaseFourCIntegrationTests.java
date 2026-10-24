package com.dartwatch;

import com.dartwatch.collection.*;
import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.List;
import javax.sql.DataSource;
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

import static com.dartwatch.support.DartFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres")
@SpringBootTest
@AutoConfigureMockMvc
class PhaseFourCIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    private static final Instant START = Instant.parse("2026-10-24T03:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }
    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @MockitoBean OpenDartClient client;
    @MockitoBean NotificationSender sender;
    @MockitoBean Clock clock;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired NotificationProperties properties;
    @Autowired NotificationWorkStore workStore;
    @Autowired CollectionPipelineService pipeline;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    private Company alpha;
    private Company beta;

    @BeforeEach
    void resetOwnedData() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        for (String table : List.of("notification_delivery", "notification_rule", "disclosure", "watched_company", "company", "notification_email_settings"))
            jdbc.update("DELETE FROM " + table);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(START);
        properties.setEnabled(false);
        alpha = companies.saveAndFlush(new Company("00000001", "000001", "Alpha 100%_!"));
        beta = companies.saveAndFlush(new Company("00000002", "000002", "Beta"));
    }

    @AfterEach
    void disable() { properties.setEnabled(false); }

    @Test
    void storedListIsNewestFirstPaginatedAndContainsIdentityAndOriginalLink() throws Exception {
        seed();
        mvc.perform(get("/api/disclosures").param("size", "2")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4)).andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].rceptNo").value("20261025000001"))
                .andExpect(jsonPath("$.items[1].rceptNo").value("20261024000003"))
                .andExpect(jsonPath("$.items[0].company.corpCode").value("00000002"))
                .andExpect(jsonPath("$.items[0].company.stockCode").value("000002"))
                .andExpect(jsonPath("$.items[0].type").value("E"))
                .andExpect(jsonPath("$.items[0].dartUrl").value("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261025000001"));
        mvc.perform(get("/api/disclosures").param("page", "1").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].rceptNo").value("20261024000002"))
                .andExpect(jsonPath("$.items[1].type").isEmpty());
        mvc.perform(get("/api/disclosures").param("page", "10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.totalElements").value(4));
        verifyNoInteractions(client, sender);
    }

    @Test
    void companyAndTypeFiltersCombineWithoutMergingFIntoE() throws Exception {
        seed();
        mvc.perform(get("/api/disclosures").param("companyId", alpha.getId().toString()).param("query", "ALPHA").param("type", "A"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].rceptNo").value("20261024000003"));
        mvc.perform(get("/api/disclosures").param("type", "F")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].type").value("F"));
        mvc.perform(get("/api/disclosures").param("companyId", alpha.getId().toString()).param("type", "E"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/disclosures").param("query", "000002")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].company.id").value(beta.getId()));
        mvc.perform(get("/api/disclosures").param("companyId", "99999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        verifyNoInteractions(client, sender);
    }

    @Test
    void companySearchTreatsSqlWildcardsAsLiteralCharacters() throws Exception {
        seed();
        mvc.perform(get("/api/disclosures").param("query", "%_!")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/disclosures").param("query", "Beta%")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
        verifyNoInteractions(client, sender);
    }

    @Test
    void restRegistrationCollectionFilteringAndMockRetryAreVisibleInReadApisWithoutDuplicates() throws Exception {
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        properties.setMaxRetries(1);
        properties.setRetryInterval(Duration.ofMinutes(1));
        workStore.synchronizeConfiguration();
        var registered = mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"companyId\":" + alpha.getId() + "}")).andExpect(status().isCreated()).andReturn();
        long watchId = mapper.readTree(registered.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(put("/api/watchlist/{id}/notification-rules/A", watchId).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/watchlist/{id}/notifications", watchId).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        var initial = disclosure("20261024000001", DAY);
        when(client.findDisclosures(any(), any(), any())).thenReturn(List.of(initial));
        mvc.perform(post("/api/collections")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(deliveries.count()).isZero();
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        var fresh = disclosure("20261025000001", DAY.plusDays(1));
        var source = List.of(initial, fresh);
        when(client.findDisclosures(any(), any(), any())).thenReturn(source);
        when(client.findDisclosures(any(), any(), any(), any(DisclosureType.class))).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1), through = invocation.getArgument(2);
            return invocation.getArgument(3) == DisclosureType.PERIODIC ? source.stream()
                    .filter(row -> !row.parsedReceiptDate().isBefore(from) && !row.parsedReceiptDate().isAfter(through)).toList() : List.of();
        });
        assertThat(pipeline.run().status()).isEqualTo("COMPLETED");
        mvc.perform(get("/api/disclosures").param("type", "A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.items[0].initialImport").value(false));
        mvc.perform(get("/api/notifications").param("status", "PENDING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.failed("CONNECTION_FAILED", true))
                .thenReturn(NotificationSender.SendResult.sent());
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.failed").value(1));
        mvc.perform(get("/api/notifications").param("status", "FAILED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].retryable").value(true)).andExpect(jsonPath("$.items[0].attemptCount").value(1));
        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)).plus(Duration.ofMinutes(1)));
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(1));
        assertThat(pipeline.run().status()).isEqualTo("COMPLETED");
        mvc.perform(get("/api/notifications").param("status", "SENT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].attemptCount").value(2));
        assertThat(disclosures.count()).isEqualTo(2);
        assertThat(deliveries.count()).isEqualTo(1);
        verify(sender, times(2)).send(any());
    }

    private void seed() {
        disclosures.saveAndFlush(new Disclosure(alpha, "20261024000001", "미분류", DAY, null, true));
        disclosures.saveAndFlush(new Disclosure(alpha, "20261024000003", "정기공시", DAY, DisclosureType.PERIODIC, false));
        disclosures.saveAndFlush(new Disclosure(alpha, "20261024000002", "외부감사", DAY, DisclosureType.EXTERNAL_AUDIT, false));
        disclosures.saveAndFlush(new Disclosure(beta, "20261025000001", "기타공시", DAY.plusDays(1), DisclosureType.OTHER, false));
    }
}
