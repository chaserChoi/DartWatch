package com.dartwatch.kakao;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class KakaoTokenService {
    private final KakaoProperties properties;
    private final KakaoTokenStore store;
    private final KakaoCrypto crypto;
    private final KakaoClient client;
    private final Clock clock;
    public KakaoTokenService(KakaoProperties properties, KakaoTokenStore store, KakaoCrypto crypto, KakaoClient client, Clock clock) {
        this.properties = properties; this.store = store; this.crypto = crypto; this.client = client; this.clock = clock;
    }
    public synchronized void connect(String code) {
        requireEnabled(); crypto.validateKey();
        var tokens = client.exchange(code);
        save(tokens, tokens.refresh(), clock.instant().plus(tokens.refreshTtl()));
    }
    public synchronized Access access() { return valid(false, null); }
    public synchronized Access refreshRejected(Access rejected) { return valid(true, rejected); }
    private Access valid(boolean force, Access rejected) {
        requireEnabled(); crypto.validateKey();
        var row = store.load();
        if (row == null || "RECONNECT_REQUIRED".equals(row.status())) throw new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED);
        if (!crypto.clientFingerprint().equals(row.fingerprint()) || "REFRESHING".equals(row.status())
                || !clock.instant().isBefore(row.refreshExpiry())) {
            store.reconnect("RECONNECT_REQUIRED"); throw new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED);
        }
        String access = crypto.decrypt(row.access(), purpose("access"));
        if ((!force || (rejected != null && !access.equals(rejected.value()))) && clock.instant().plusSeconds(30).isBefore(row.accessExpiry()))
            return new Access(access);
        String refresh = crypto.decrypt(row.refresh(), purpose("refresh"));
        store.beginRefresh(); // Durable intent: a crash during rotating refresh cannot reuse the previous token blindly.
        try {
            var tokens = client.refresh(refresh);
            String nextRefresh = tokens.refresh() == null ? refresh : tokens.refresh();
            Instant nextExpiry = tokens.refresh() == null ? row.refreshExpiry() : clock.instant().plus(tokens.refreshTtl());
            save(tokens, nextRefresh, nextExpiry);
            return new Access(tokens.access());
        } catch (RuntimeException ex) {
            try {
                if (ex instanceof KakaoFailure f && (f.kind() == KakaoFailure.Kind.RATE_LIMIT || f.kind() == KakaoFailure.Kind.CONFIGURATION))
                    store.restoreAfterRejectedRefresh();
                else store.reconnect("REFRESH_FAILED");
            } catch (RuntimeException ignored) { }
            if (ex instanceof KakaoFailure failure) throw failure;
            throw new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED);
        }
    }
    private void save(KakaoClient.Tokens tokens, String refresh, Instant expiry) {
        store.save(crypto.clientFingerprint(), crypto.encrypt(tokens.access(), purpose("access")), crypto.encrypt(refresh, purpose("refresh")),
                clock.instant().plus(tokens.accessTtl()), expiry);
    }
    private String purpose(String kind) { return "dartwatch:kakao:" + kind + ":" + crypto.clientFingerprint(); }
    public synchronized void reconnect() { store.reconnect("RECONNECT_REQUIRED"); }
    public synchronized Status status() {
        var row = store.load();
        if (row == null) return new Status("DISCONNECTED", null, null, null);
        if ("REFRESHING".equals(row.status()) || (!"RECONNECT_REQUIRED".equals(row.status()) && !clock.instant().isBefore(row.refreshExpiry()))) {
            store.reconnect("RECONNECT_REQUIRED"); return new Status("RECONNECT_REQUIRED", null, null, "RECONNECT_REQUIRED");
        }
        return new Status(row.status(), row.accessExpiry(), row.refreshExpiry(), row.error());
    }
    private void requireEnabled() { if (!properties.isEnabled()) throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    public record Access(String value) { @Override public String toString() { return "KakaoAccess[redacted]"; } }
    public record Status(String status, Instant accessExpiresAt, Instant refreshExpiresAt, String error) { }
}
