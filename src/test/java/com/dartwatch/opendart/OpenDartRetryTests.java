package com.dartwatch.opendart;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static com.dartwatch.opendart.OpenDartException.Kind.*;
import static com.dartwatch.support.DartFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenDartRetryTests {
    private final LocalDate day = LocalDate.of(2026, 10, 24);
    private final Clock clock = mock(Clock.class);
    private final List<Long> delays = new ArrayList<>();
    private final OpenDartProperties properties = new OpenDartProperties();
    private MockRestServiceServer server;
    private OpenDartClient client;
    private OpenDartRequestPolicy policy;

    @BeforeEach
    void setup() {
        properties.setApiKey("placeholder");
        properties.setPageSize(2);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T03:00:00Z"));
        policy = new OpenDartRequestPolicy(properties, clock, delays::add, () -> 1.0);
        var builder = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenDartClient(builder.build(), properties, new CorpCodeParser(), new ObjectMapper(), policy);
    }

    @Test
    void retriesHttpServerAndBodySystemFailureThenSucceeds() {
        server.expect(queryParam("page_no", "1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                "{\"status\":\"900\",\"message\":\"secret\"}", MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                page(1, 2, 1, 1, item("20261024000001", "20261024")), MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", day, day)).hasSize(1);
        assertThat(delays).containsExactly(1000L, 2000L);
        server.verify();
    }

    @Test
    void retriesOnlyFailedPageAndNeverAppendsItTwice() {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(page(1, 2, 3, 2,
                item("20261024000001", "20261024") + "," + item("20261024000002", "20261024")), MediaType.APPLICATION_JSON));
        server.expect(queryParam("page_no", "2")).andRespond(request -> { throw new SocketTimeoutException("secret"); });
        server.expect(queryParam("page_no", "2")).andRespond(withSuccess(page(2, 2, 3, 2,
                item("20261024000003", "20261024")), MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", day, day)).hasSize(3);
        assertThat(delays).containsExactly(1000L);
        server.verify();
    }

    @Test
    void connectionFailureExhaustsExactlyThreeAttemptsAndKeepsSecretsOut() {
        for (int i = 0; i < 3; i++) {
            server.expect(queryParam("page_no", "1")).andRespond(request -> { throw new ConnectException("secret URI"); });
        }
        assertThatThrownBy(() -> client.findDisclosures("00000001", day, day))
                .isInstanceOfSatisfying(OpenDartException.class, ex -> {
                    assertThat(ex.getKind()).isEqualTo(NETWORK);
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.getMessage()).doesNotContain("secret", "placeholder");
                });
        assertThat(delays).hasSize(2);
        server.verify();
    }

    @Test
    void quotaBodyBlocksDirectoryAndLaterCyclesWithoutExtendingCooldown() {
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                "{\"status\":\"020\",\"message\":\"quota\"}", MediaType.APPLICATION_JSON));
        assertRateLimited(() -> client.findDisclosures("00000001", day, day));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T03:59:59Z"));
        assertRateLimited(client::downloadCompanies);
        assertRateLimited(() -> client.findDisclosures("00000001", day, day));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-24T04:00:00Z"));
        server.verify();
        server.reset();
        server.expect(queryParam("page_no", "1")).andRespond(withSuccess(
                "{\"status\":\"013\",\"message\":\"empty\"}", MediaType.APPLICATION_JSON));
        assertThat(client.findDisclosures("00000001", day, day)).isEmpty();
        assertThat(delays).isEmpty();
        server.verify();
    }

    @Test
    void http429ImmediatelyBlocksOtherRequests() {
        server.expect(queryParam("page_no", "1")).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertRateLimited(() -> client.findDisclosures("00000001", day, day));
        assertRateLimited(client::downloadCompanies);
        assertThat(delays).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400,HTTP", "401,AUTHENTICATION", "403,AUTHENTICATION", "404,HTTP"})
    void httpClientErrorsAreMappedAndNeverRetried(int status, OpenDartException.Kind kind) {
        server.expect(queryParam("page_no", "1")).andRespond(withStatus(HttpStatus.valueOf(status)).body("secret"));
        assertThatThrownBy(() -> client.findDisclosures("00000001", day, day))
                .isInstanceOfSatisfying(OpenDartException.class, ex -> assertThat(ex.getKind()).isEqualTo(kind));
        assertThat(delays).isEmpty();
        server.verify();
    }

    @Test
    void directoryXmlSystemErrorUsesTheSameRetryPolicy() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("corpCode.xml"))).andRespond(withSuccess(
                "<result><status>800</status><message>maintenance</message></result>", MediaType.APPLICATION_XML));
        server.expect(requestTo(org.hamcrest.Matchers.containsString("corpCode.xml"))).andRespond(withSuccess(
                "<result><status>010</status><message>secret</message></result>", MediaType.APPLICATION_XML));
        assertThatThrownBy(client::downloadCompanies).isInstanceOfSatisfying(OpenDartException.class,
                ex -> assertThat(ex.getKind()).isEqualTo(AUTHENTICATION));
        assertThat(delays).containsExactly(1000L);
        server.verify();
    }

    @ParameterizedTest
    @EnumSource(value = OpenDartException.Kind.class, names = {"CONFIGURATION", "AUTHENTICATION", "RATE_LIMIT",
            "INVALID_REQUEST", "HTTP", "API_REJECTED", "INVALID_RESPONSE", "INTERRUPTED"})
    void permanentOrUntrustedFailuresAreNeverRetried(OpenDartException.Kind kind) {
        @SuppressWarnings("unchecked") Supplier<Object> request = mock(Supplier.class);
        when(request.get()).thenThrow(new OpenDartException(kind));
        assertThatThrownBy(() -> policy.execute(request)).isInstanceOf(OpenDartException.class);
        verify(request, times(1)).get();
        assertThat(delays).isEmpty();
    }

    @Test
    void exponentialBackoffIsCappedAndJitterStaysWithinHalfToFullDelay() {
        properties.setMaxRetries(5);
        properties.setRetryMaxDelay(Duration.ofSeconds(3));
        @SuppressWarnings("unchecked") Supplier<Object> request = mock(Supplier.class);
        when(request.get()).thenThrow(new OpenDartException(TIMEOUT));
        assertThatThrownBy(() -> policy.execute(request)).isInstanceOf(OpenDartException.class);
        assertThat(delays).containsExactly(1000L, 2000L, 3000L, 3000L, 3000L);
        delays.clear();
        var jittered = new OpenDartRequestPolicy(properties, clock, delays::add, () -> 0.0);
        assertThatThrownBy(() -> jittered.execute(request)).isInstanceOf(OpenDartException.class);
        assertThat(delays).containsExactly(500L, 1000L, 1500L, 1500L, 1500L);
    }

    @Test
    void shutdownInterruptStopsRetryAndPreservesInterruptFlag() {
        var interrupted = new OpenDartRequestPolicy(properties, clock, millis -> { throw new InterruptedException(); }, () -> 0);
        try {
            assertThatThrownBy(() -> interrupted.execute(() -> { throw new OpenDartException(TIMEOUT); }))
                    .isInstanceOfSatisfying(OpenDartException.class, ex -> assertThat(ex.getKind()).isEqualTo(INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void rejectsUnboundedRetryConfiguration() {
        assertThatThrownBy(() -> properties.setMaxRetries(6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setRetryInitialDelay(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setRetryMaxDelay(Duration.ofMinutes(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setRateLimitCooldown(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertRateLimited(java.util.concurrent.Callable<?> request) {
        assertThatThrownBy(request::call).isInstanceOfSatisfying(OpenDartException.class,
                ex -> assertThat(ex.getKind()).isEqualTo(RATE_LIMIT));
    }
}
