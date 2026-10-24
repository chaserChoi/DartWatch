package com.dartwatch.opendart;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dartwatch.disclosure.DisclosureType;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.dartwatch.support.DartFixtures.*;

class OpenDartClientTests {
    private final LocalDate from = LocalDate.of(2026, 10, 20);
    private final LocalDate through = LocalDate.of(2026, 10, 24);
    private MockRestServiceServer server;
    private OpenDartClient client;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api");
        server = MockRestServiceServer.bindTo(builder).build();
        var properties = new OpenDartProperties();
        properties.setApiKey("placeholder");
        properties.setPageSize(2);
        client = new OpenDartClient(builder.build(), properties, new CorpCodeParser(), new ObjectMapper());
    }

    @Test
    void mapsAllPagesAndUsesExplicitCorrectionInclusiveQuery() {
        server.expect(queryParam("page_no", "1")).andExpect(queryParam("page_count", "2"))
                .andExpect(queryParam("crtfc_key", "placeholder")).andExpect(queryParam("corp_code", "00000001"))
                .andExpect(queryParam("bgn_de", "20261020")).andExpect(queryParam("end_de", "20261024"))
                .andExpect(queryParam("last_reprt_at", "N")).andExpect(queryParam("sort_mth", "asc"))
                .andRespond(withSuccess(page(1, 2, 3, 2, item("20261023000001", "20261023") + ","
                        + item("20261023000002", "20261023")), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2")).andRespond(withSuccess(
                page(2, 2, 3, 2, item("20261024000001", "20261024")), MediaType.APPLICATION_JSON));
        var records = client.findDisclosures("00000001", from, through);
        assertThat(records).hasSize(3);
        assertThat(records.getFirst().filerName()).isEqualTo("Test filer");
        assertThat(records.getFirst().remarks()).isEqualTo("정");
        assertThat(records.getFirst().parsedReceiptDate()).hasToString("2026-10-23");
        server.verify();
    }

    @Test
    void noDataIsANormalEmptyResult() {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                "{\"status\":\"013\",\"message\":\"No results\"}", MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", from, through)).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"010,AUTHENTICATION", "011,AUTHENTICATION", "012,AUTHENTICATION", "020,RATE_LIMIT",
            "800,UPSTREAM_SYSTEM", "900,UPSTREAM_SYSTEM", "100,API_REJECTED"})
    void classifiesBodyStatusWithoutLeakingUpstreamMessage(String status, OpenDartException.Kind kind) {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                "{\"status\":\"" + status + "\",\"message\":\"sensitive content\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findDisclosures("00000001", from, through))
                .isInstanceOfSatisfying(OpenDartException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(kind);
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getMessage()).doesNotContain("sensitive", "placeholder");
                    assertThat(ex.getCause()).isNull();
                });
    }

    @Test
    void httpErrorsDoNotCarryRequestUriOrResponseBody() {
        server.expect(queryParam("page_no", "1"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("sensitive content"));
        assertKind(OpenDartException.Kind.HTTP);
    }

    @Test
    void networkTimeoutIsDistinctFromConnectionFailure() {
        server.expect(queryParam("page_no", "1")).andRespond(request -> { throw new SocketTimeoutException("sensitive URI"); });
        assertKind(OpenDartException.Kind.TIMEOUT);
    }

    @Test
    void connectionFailureIsSanitized() {
        server.expect(queryParam("page_no", "1")).andRespond(request -> { throw new ConnectException("sensitive URI"); });
        assertKind(OpenDartException.Kind.NETWORK);
    }

    @Test
    void laterPageFailureDoesNotReturnPartialResults() {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(page(1, 2, 3, 2,
                item("20261023000001", "20261023") + "," + item("20261023000002", "20261023")), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2"))
                .andRespond(withSuccess("{\"status\":\"900\",\"message\":\"failure\"}", MediaType.APPLICATION_JSON));
        assertKind(OpenDartException.Kind.UPSTREAM_SYSTEM);
    }

    @Test
    void changedPaginationMetadataIsRejectedRatherThanAdvancingCursor() {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(page(1, 2, 3, 2,
                item("20261023000001", "20261023") + "," + item("20261023000002", "20261023")), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2")).andRespond(withSuccess(page(2, 2, 4, 2,
                item("20261024000001", "20261024")), MediaType.APPLICATION_JSON));
        assertKind(OpenDartException.Kind.INVALID_RESPONSE);
    }

    @Test
    void missingKeyFailsBeforeMakingARequest() {
        var properties = new OpenDartProperties();
        var noKey = new OpenDartClient(RestClient.create(), properties, new CorpCodeParser(), new ObjectMapper());
        assertThatThrownBy(noKey::downloadCompanies).isInstanceOfSatisfying(OpenDartException.class,
                ex -> assertThat(ex.getKind()).isEqualTo(OpenDartException.Kind.CONFIGURATION));
    }

    @ParameterizedTest
    @CsvSource({"A,PERIODIC", "B,MATERIAL_EVENT", "C,ISSUANCE", "D,SHAREHOLDING", "E,OTHER"})
    void usesOfficialTypeCodeWithoutChangingCorrectionPolicy(String code, DisclosureType type) {
        server.expect(queryParam("pblntf_ty", code)).andExpect(queryParam("last_reprt_at", "N"))
                .andRespond(withSuccess(page(1, 2, 1, 1, item("20261023000001", "20261023")), MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", from, through, type)).hasSize(1);
        server.verify();
    }

    @Test
    void paginatesTypedSearchUsingTheSameTypeOnEveryPage() {
        server.expect(queryParam("pblntf_ty", "A")).andExpect(queryParam("page_no", "1"))
                .andRespond(withSuccess(page(1, 2, 3, 2, item("20261023000001", "20261023") + ","
                        + item("20261023000002", "20261023")), MediaType.APPLICATION_JSON));
        server.expect(queryParam("pblntf_ty", "A")).andExpect(queryParam("page_no", "2"))
                .andRespond(withSuccess(page(2, 2, 3, 2, item("20261024000001", "20261024")), MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", from, through, DisclosureType.PERIODIC)).hasSize(3);
        server.verify();
    }

    private void assertKind(OpenDartException.Kind kind) {
        assertThatThrownBy(() -> client.findDisclosures("00000001", from, through))
                .isInstanceOfSatisfying(OpenDartException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(kind);
                    assertThat(ex.getMessage()).doesNotContain("sensitive", "placeholder");
                    assertThat(ex.getCause()).isNull();
                });
    }
}
