package com.dartwatch.kakao;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class KakaoTokenStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public KakaoTokenStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Transactional(readOnly = true)
    public Stored load() {
        return jdbc.query("SELECT * FROM kakao_connection WHERE singleton_id=1", (r, i) -> new Stored(r.getString("status"),
                r.getString("client_fingerprint"), r.getString("access_ciphertext"), r.getString("refresh_ciphertext"),
                instant(r.getTimestamp("access_expires_at")), instant(r.getTimestamp("refresh_expires_at")), r.getString("last_error")))
                .stream().findFirst().orElse(null);
    }
    @Transactional
    public void save(String fingerprint, String access, String refresh, Instant accessExpiry, Instant refreshExpiry) {
        jdbc.update("""
                INSERT INTO kakao_connection VALUES (1,'CONNECTED',?,?,?,?,?,?,NULL)
                ON CONFLICT (singleton_id) DO UPDATE SET status='CONNECTED',client_fingerprint=EXCLUDED.client_fingerprint,
                    access_ciphertext=EXCLUDED.access_ciphertext,refresh_ciphertext=EXCLUDED.refresh_ciphertext,
                    access_expires_at=EXCLUDED.access_expires_at,refresh_expires_at=EXCLUDED.refresh_expires_at,
                    updated_at=EXCLUDED.updated_at,last_error=NULL
                """, fingerprint, access, refresh, Timestamp.from(accessExpiry), Timestamp.from(refreshExpiry), Timestamp.from(clock.instant()));
    }
    @Transactional
    public void beginRefresh() {
        if (jdbc.update("UPDATE kakao_connection SET status='REFRESHING',updated_at=? WHERE singleton_id=1 AND status='CONNECTED'",
                Timestamp.from(clock.instant())) != 1) throw new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED);
    }
    @Transactional
    public void restoreAfterRejectedRefresh() {
        jdbc.update("UPDATE kakao_connection SET status='CONNECTED',updated_at=? WHERE singleton_id=1 AND status='REFRESHING'", Timestamp.from(clock.instant()));
    }
    @Transactional
    public void reconnect(String error) {
        if (!error.matches("[A-Z_]{1,80}")) throw new IllegalArgumentException("Invalid error code");
        jdbc.update("""
                UPDATE kakao_connection SET status='RECONNECT_REQUIRED',access_ciphertext=NULL,refresh_ciphertext=NULL,
                    access_expires_at=NULL,refresh_expires_at=NULL,last_error=?,updated_at=? WHERE singleton_id=1
                """, error, Timestamp.from(clock.instant()));
    }
    private static Instant instant(Timestamp v) { return v == null ? null : v.toInstant(); }
    public record Stored(String status, String fingerprint, String access, String refresh,
                         Instant accessExpiry, Instant refreshExpiry, String error) {
        @Override public String toString() { return "KakaoStored[redacted]"; }
    }
}
