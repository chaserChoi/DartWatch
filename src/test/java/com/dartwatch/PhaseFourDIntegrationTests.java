package com.dartwatch;

import com.dartwatch.collection.CollectionPipelineService;
import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class PhaseFourDIntegrationTests {
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
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @Autowired Flyway flyway;
    @Autowired CollectionPipelineService pipeline;
    @Autowired NotificationProperties properties;
    @Autowired NotificationWorkStore workStore;

    @AfterEach
    void disable() { properties.setEnabled(false); }

    @Test
    void threeCompanyMvpFlowKeepsCodeMappingsMonitoringBaselinesAndDeliveryIdempotency() throws Exception {
        SCHEMA.verifyOwnership(dataSource);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(START);
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        workStore.synchronizeConfiguration();
        // Company names/stock codes illustrate the requested scenario; corp codes are deliberately fictitious.
        var names = List.of("삼성전자", "SK하이닉스", "NAVER");
        var stocks = List.of("005930", "000660", "035420");
        var types = List.of(DisclosureType.PERIODIC, DisclosureType.MATERIAL_EVENT, DisclosureType.ISSUANCE);
        var directory = new ArrayList<CorpRecord>();
        for (int index = 0; index < 3; index++)
            directory.add(new CorpRecord("0000000" + (index + 1), names.get(index), stocks.get(index), DAY));
        when(client.downloadCompanies()).thenReturn(directory);
        mvc.perform(post("/api/companies/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.companyCount").value(3));
        mvc.perform(post("/api/companies/sync")).andExpect(status().isOk()).andExpect(jsonPath("$.downloaded").value(false));
        var watches = new ArrayList<Long>();
        for (int index = 0; index < 3; index++) {
            String corp = directory.get(index).corpCode();
            var search = mvc.perform(get("/api/companies").param("query", stocks.get(index))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.items[0].corpCode").value(corp))
                    .andExpect(jsonPath("$.items[0].stockCode").value(stocks.get(index))).andReturn();
            mvc.perform(get("/api/companies").param("query", names.get(index))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].corpCode").value(corp));
            long companyId = mapper.readTree(search.getResponse().getContentAsString()).at("/items/0/id").asLong();
            var registered = mvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"companyId\":" + companyId + "}")).andExpect(status().isCreated()).andReturn();
            long watchId = mapper.readTree(registered.getResponse().getContentAsString()).path("id").asLong();
            watches.add(watchId);
            mvc.perform(put("/api/watchlist/{id}/notification-rules/{type}", watchId, types.get(index).getDartCode())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isOk());
            mvc.perform(patch("/api/watchlist/{id}/notifications", watchId).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"enabled\":true}")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/watchlist")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        when(client.findDisclosures(anyString(), any(), any())).thenAnswer(invocation -> {
            String corp = invocation.getArgument(0);
            var rows = new ArrayList<DartDisclosure>();
            rows.add(record(corp, DAY));
            if (((LocalDate) invocation.getArgument(2)).isAfter(DAY)) rows.add(record(corp, DAY.plusDays(1)));
            return rows;
        });
        mvc.perform(patch("/api/watchlist/{id}/monitoring", watches.get(2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.monitoringEnabled").value(false));
        mvc.perform(post("/api/collections")).andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(2));
        verify(client, never()).findDisclosures(eq("00000003"), any(), any());
        mvc.perform(patch("/api/watchlist/{id}/monitoring", watches.get(2)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        mvc.perform(post("/api/collections")).andExpect(status().isOk()).andExpect(jsonPath("$.companies.length()").value(3));
        mvc.perform(get("/api/disclosures")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/notifications")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        when(clock.instant()).thenReturn(START.plus(Duration.ofDays(1)));
        when(client.findDisclosures(anyString(), any(), any(), any(DisclosureType.class))).thenAnswer(invocation -> {
            String corp = invocation.getArgument(0);
            int index = Integer.parseInt(corp) - 1;
            if (invocation.getArgument(3) != types.get(index)) return List.of();
            LocalDate from = invocation.getArgument(1), through = invocation.getArgument(2);
            return List.of(record(corp, DAY), record(corp, DAY.plusDays(1))).stream()
                    .filter(row -> !row.parsedReceiptDate().isBefore(from) && !row.parsedReceiptDate().isAfter(through)).toList();
        });
        assertThat(pipeline.run().status()).isEqualTo("COMPLETED");
        mvc.perform(get("/api/disclosures")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(6));
        mvc.perform(get("/api/notifications").param("status", "PENDING")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        verifyNoInteractions(sender);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(3));
        assertThat(pipeline.run().status()).isEqualTo("COMPLETED");
        mvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content("{\"confirmSend\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sent").value(0));
        mvc.perform(get("/api/notifications").param("status", "SENT")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        verify(sender, times(3)).send(any());
        verify(client, times(1)).downloadCompanies();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
        assertThatCode(flyway::validate).doesNotThrowAnyException();
    }

    private DartDisclosure record(String corp, LocalDate day) {
        return new DartDisclosure(corp, "Mock company " + corp, day.toString().replace("-", "") + "00000" + Integer.parseInt(corp),
                "Mock disclosure", day.toString().replace("-", ""), "Mock filer", null);
    }
}
