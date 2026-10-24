package com.dartwatch.kakao;

import com.dartwatch.support.OwnedPostgresSchema;
import java.time.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static com.dartwatch.kakao.KakaoFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("postgres")
@SpringBootTest
class KakaoPersistenceTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }
    @AfterAll static void cleanup(@Autowired DataSource source) throws Exception { SCHEMA.drop(source); }
    @MockitoBean KakaoClient client;
    @MockitoBean Clock clock;
    @Autowired KakaoTokenStore store;
    @Autowired KakaoTokenService service;
    @Autowired KakaoCrypto crypto;
    @Autowired KakaoProperties p;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired Flyway flyway;
    @BeforeEach void setup() throws Exception {
        SCHEMA.verifyOwnership(source);
        jdbc.update("DELETE FROM kakao_connection");
        when(clock.instant()).thenReturn(NOW);
        var config = properties();
        p.setEnabled(true); p.setRestApiKey(config.getRestApiKey()); p.setEncryptionKey(config.getEncryptionKey());
        when(client.exchange("mock-code")).thenReturn(initial());
    }
    @AfterEach void disable() { p.setEnabled(false); }
    @Test void v5EncryptsTokensAndRestoresAccessAfterServiceRestartWithoutHttp() {
        assertThat(flyway.info().current().getVersion().toString()).isEqualTo("5");
        service.connect("mock-code");
        var row = store.load();
        assertThat(row.access()).startsWith("v1:").doesNotContain("mock-access");
        assertThat(row.refresh()).doesNotContain("mock-refresh");
        assertThat(row.accessExpiry()).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(row.refreshExpiry()).isEqualTo(NOW.plus(Duration.ofDays(60)));
        var restarted = new KakaoTokenService(p, store, crypto, client, clock);
        assertThat(restarted.access().value()).isEqualTo("mock-access");
        assertThat(restarted.status().status()).isEqualTo("CONNECTED");
        verify(client, never()).refresh(any());
        assertThat(row.toString()).doesNotContain(row.access(), "mock-access");
    }
    @Test void refreshWithoutRotationPreservesRefreshAndOriginalExpiry() {
        service.connect("mock-code");
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        when(client.refresh("mock-refresh")).thenReturn(new KakaoClient.Tokens("mock-new", null, Duration.ofHours(6), null));
        assertThat(service.access().value()).isEqualTo("mock-new");
        assertThat(store.load().refreshExpiry()).isEqualTo(NOW.plus(Duration.ofDays(60)));
        assertThat(crypto.decrypt(store.load().refresh(), purpose("refresh"))).isEqualTo("mock-refresh");
        assertThat(service.access().value()).isEqualTo("mock-new");
        verify(client, times(1)).refresh("mock-refresh");
    }
    @Test void rotatedRefreshReplacesCiphertextAndExpiry() {
        service.connect("mock-code");
        when(client.refresh("mock-refresh")).thenReturn(new KakaoClient.Tokens("mock-new", "mock-rotated", Duration.ofHours(6), Duration.ofDays(60)));
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(40)));
        service.access();
        assertThat(crypto.decrypt(store.load().refresh(), purpose("refresh"))).isEqualTo("mock-rotated");
        assertThat(store.load().refreshExpiry()).isEqualTo(NOW.plus(Duration.ofDays(100)));
    }
    @Test void concurrentExpiredAccessAndStaleRejectedTokenOnlyRefreshOnce() throws Exception {
        service.connect("mock-code");
        when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        when(client.refresh("mock-refresh")).thenReturn(new KakaoClient.Tokens("mock-new", null, Duration.ofHours(6), null));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            Callable<String> work = () -> { gate.await(); return service.access().value(); };
            var one = executor.submit(work); var two = executor.submit(work); gate.countDown();
            assertThat(one.get(5, TimeUnit.SECONDS)).isEqualTo("mock-new");
            assertThat(two.get(5, TimeUnit.SECONDS)).isEqualTo("mock-new");
        }
        assertThat(service.refreshRejected(new KakaoTokenService.Access("mock-access")).value()).isEqualTo("mock-new");
        verify(client, times(1)).refresh("mock-refresh");
    }
    @Test void expiredRefreshRequiresReconnectAndErasesUnusableTokens() {
        service.connect("mock-code"); when(clock.instant()).thenReturn(NOW.plus(Duration.ofDays(60)));
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected(); verify(client, never()).refresh(any());
    }
    @Test void revokedRefreshRequiresReconnectWithoutLooping() {
        service.connect("mock-code"); when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        when(client.refresh(any())).thenThrow(new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED));
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected();
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        verify(client, times(1)).refresh(any());
    }
    @Test void durableRefreshingMarkerSurvivesRestartAndNeverReusesUncertainRefresh() {
        service.connect("mock-code"); store.beginRefresh();
        var restarted = new KakaoTokenService(p, store, crypto, client, clock);
        assertThatThrownBy(restarted::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected(); verify(client, never()).refresh(any());
    }
    @Test void knownQuotaRejectionRestoresOldTokensForLaterRetry() {
        service.connect("mock-code"); when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        String prior = store.load().refresh();
        when(client.refresh(any())).thenThrow(new KakaoFailure(KakaoFailure.Kind.RATE_LIMIT, Duration.ofDays(1)));
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertThat(store.load().status()).isEqualTo("CONNECTED");
        assertThat(store.load().refresh()).isEqualTo(prior);
    }
    @Test void transportDuringRotationQuarantinesTokensInsteadOfRepeatingRequest() {
        service.connect("mock-code"); when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        when(client.refresh(any())).thenThrow(new KakaoFailure(KakaoFailure.Kind.TRANSPORT));
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected();
    }
    @Test void changedApplicationRequiresReconnectAndWrongEncryptionKeyCannotReplaceStoredData() {
        service.connect("mock-code"); var prior = store.load();
        byte[] wrong = new byte[32]; wrong[0] = 1;
        p.setEncryptionKey(java.util.Base64.getEncoder().encodeToString(wrong));
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertThat(store.load()).isEqualTo(prior);
        p.setRestApiKey("different-mock-app");
        assertThatThrownBy(service::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected();
    }
    @Test void databaseRejectsPlaintextNullTokensAndAdditionalAccounts() {
        service.connect("mock-code");
        assertThatThrownBy(() -> jdbc.update("UPDATE kakao_connection SET access_ciphertext='plaintext'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE kakao_connection SET refresh_ciphertext=NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE kakao_connection SET singleton_id=2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void databaseFailureAfterRemoteRotationNeverReturnsUnpersistedToken() {
        service.connect("mock-code"); when(clock.instant()).thenReturn(NOW.plus(Duration.ofHours(6)));
        var broken = mock(KakaoTokenStore.class);
        when(broken.load()).thenAnswer(ignored -> store.load());
        doAnswer(ignored -> { store.beginRefresh(); return null; }).when(broken).beginRefresh();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("mock DB outage"))
                .when(broken).save(anyString(), anyString(), anyString(), any(), any());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("mock DB outage")).when(broken).reconnect(anyString());
        when(client.refresh(any())).thenReturn(new KakaoClient.Tokens("mock-new", "mock-rotated", Duration.ofHours(6), Duration.ofDays(60)));
        var disrupted = new KakaoTokenService(p, broken, crypto, client, clock);
        assertThatThrownBy(disrupted::access).isInstanceOf(KakaoFailure.class);
        assertThat(store.load().status()).isEqualTo("REFRESHING");
        var restarted = new KakaoTokenService(p, store, crypto, client, clock);
        assertThatThrownBy(restarted::access).isInstanceOf(KakaoFailure.class);
        assertDisconnected(); verify(client, times(1)).refresh(any());
    }
    private String purpose(String kind) { return "dartwatch:kakao:" + kind + ":" + crypto.clientFingerprint(); }
    private void assertDisconnected() {
        assertThat(store.load().status()).isEqualTo("RECONNECT_REQUIRED");
        assertThat(store.load().access()).isNull(); assertThat(store.load().refresh()).isNull();
    }
}
