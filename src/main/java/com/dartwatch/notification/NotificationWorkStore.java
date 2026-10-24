package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.DisclosureType;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationWorkStore {
    // Same watch/rule/detection predicates as the 3A preview, plus the recipient's global delivery baseline.
    private static final String ELIGIBLE = """
            d.initial_import = FALSE
            AND d.disclosure_type IN ('PERIODIC','MATERIAL_EVENT','ISSUANCE','SHAREHOLDING','OTHER')
            AND EXISTS (SELECT 1 FROM notification_email_settings g WHERE g.recipient = :recipient
                AND g.enabled = TRUE AND d.collected_at > g.enabled_since)
            AND EXISTS (SELECT 1 FROM watched_company w JOIN notification_rule r ON r.watched_company_id = w.id
                WHERE w.company_id = d.company_id AND w.monitoring_enabled = TRUE AND w.notifications_enabled = TRUE
                AND d.collected_at > w.notifications_enabled_since AND r.enabled = TRUE
                AND r.channel = 'EMAIL' AND r.disclosure_type = d.disclosure_type AND d.collected_at > r.enabled_since)
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationWorkStore(NamedParameterJdbcTemplate jdbc, NotificationProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void synchronizeConfiguration() {
        if (!properties.isEnabled()) {
            jdbc.update("UPDATE notification_email_settings SET enabled = FALSE WHERE enabled = TRUE", new MapSqlParameterSource());
            jdbc.update("""
                    UPDATE notification_delivery SET status='CANCELLED',last_error='NOTIFICATIONS_DISABLED',
                        retryable=FALSE,next_attempt_at=NULL,version=version+1
                    WHERE recipient IS NOT NULL AND (status='PENDING' OR (status='FAILED' AND retryable=TRUE))
                    """, new MapSqlParameterSource());
            return;
        }
        var params = parameters();
        EmailAddress.normalize(properties.getMailFrom());
        jdbc.update("UPDATE notification_email_settings SET enabled=FALSE WHERE recipient<>:recipient", params);
        jdbc.update("""
                INSERT INTO notification_email_settings (recipient,enabled,enabled_since) VALUES (:recipient,TRUE,:now)
                ON CONFLICT (recipient) DO UPDATE SET enabled=TRUE,
                    enabled_since=CASE WHEN notification_email_settings.enabled THEN notification_email_settings.enabled_since ELSE EXCLUDED.enabled_since END
                """, params);
    }

    @Transactional
    public int enqueue(Long watchId) {
        var params = parameters().addValue("watchId", watchId).addValue("batchSize", properties.getBatchSize());
        return jdbc.update("""
                INSERT INTO notification_delivery (disclosure_id,channel,recipient,status,attempt_count,created_at,
                    version,next_attempt_at,retryable)
                SELECT d.id,'EMAIL',:recipient,'PENDING',0,:now,0,:now,FALSE FROM disclosure d
                WHERE EXISTS (SELECT 1 FROM watched_company w WHERE w.id=:watchId AND w.company_id=d.company_id)
                AND NOT EXISTS (SELECT 1 FROM notification_delivery n WHERE n.disclosure_id=d.id AND n.channel='EMAIL'
                    AND (n.recipient=:recipient OR n.recipient IS NULL))
                AND
                """ + ELIGIBLE + " ORDER BY d.collected_at,d.id LIMIT :batchSize ON CONFLICT DO NOTHING", params);
    }

    @Transactional
    public int recoverStale() {
        return jdbc.update("""
                UPDATE notification_delivery SET status='UNKNOWN',last_error='RECOVERED_IN_FLIGHT',claim_token=NULL,
                    retryable=FALSE,next_attempt_at=NULL,version=version+1
                WHERE status='PROCESSING' AND last_attempt_at <= :staleBefore
                """, new MapSqlParameterSource("staleBefore", Timestamp.from(clock.instant().minus(properties.getProcessingTimeout()))));
    }

    @Transactional
    public int stopExhaustedRetries() {
        return jdbc.update("""
                UPDATE notification_delivery SET retryable=FALSE,next_attempt_at=NULL,version=version+1
                WHERE status='FAILED' AND retryable=TRUE AND attempt_count>=:maxAttempts
                """, new MapSqlParameterSource("maxAttempts", properties.getMaxRetries() + 1));
    }

    @Transactional
    public int cancelIneligible() {
        return jdbc.update("""
                UPDATE notification_delivery n SET status='CANCELLED',last_error='POLICY_CHANGED',retryable=FALSE,
                    next_attempt_at=NULL,version=version+1
                WHERE n.recipient IS NOT NULL AND (n.status='PENDING' OR (n.status='FAILED' AND n.retryable=TRUE)) AND
                    (n.recipient<>:recipient OR NOT EXISTS (SELECT 1 FROM disclosure d WHERE d.id=n.disclosure_id AND
                """ + ELIGIBLE + "))", parameters());
    }

    @Transactional
    public Claim claimOne() {
        var params = parameters().addValue("maxAttempts", properties.getMaxRetries() + 1);
        var ids = jdbc.queryForList("""
                SELECT n.id FROM notification_delivery n JOIN disclosure d ON d.id=n.disclosure_id
                WHERE n.recipient=:recipient AND n.channel='EMAIL' AND n.next_attempt_at<=:now
                AND n.attempt_count<:maxAttempts AND (n.status='PENDING' OR (n.status='FAILED' AND n.retryable=TRUE))
                AND
                """ + ELIGIBLE + " ORDER BY n.next_attempt_at,n.id LIMIT 1 FOR UPDATE OF n SKIP LOCKED", params, Long.class);
        if (ids.isEmpty()) return null;
        Long id = ids.getFirst();
        UUID token = UUID.randomUUID();
        params.addValue("id", id).addValue("token", token);
        jdbc.update("""
                UPDATE notification_delivery SET status='PROCESSING',attempt_count=attempt_count+1,last_attempt_at=:now,
                    claim_token=:token,last_error=NULL,next_attempt_at=NULL,retryable=FALSE,version=version+1 WHERE id=:id
                """, params);
        return jdbc.queryForObject("""
                SELECT n.id,n.recipient,n.attempt_count,coalesce(d.company_name,c.name) AS company_name,d.report_name,d.disclosure_type,
                    d.receipt_date,d.rcept_no FROM notification_delivery n JOIN disclosure d ON d.id=n.disclosure_id
                    JOIN company c ON c.id=d.company_id WHERE n.id=:id
                """, params, (row, index) -> new Claim(row.getLong("id"), token, new NotificationSender.Message(
                row.getString("recipient"), row.getString("company_name"), row.getString("report_name"),
                DisclosureType.valueOf(row.getString("disclosure_type")).getDartCode(),
                row.getDate("receipt_date").toLocalDate(), row.getString("rcept_no")), row.getInt("attempt_count")));
    }

    @Transactional(readOnly = true)
    public boolean isStillEligible(Claim claim) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_delivery n JOIN disclosure d ON d.id=n.disclosure_id
                    WHERE n.id=:id AND n.status='PROCESSING' AND n.claim_token=:token AND n.recipient=:recipient AND
                """ + ELIGIBLE + ")", parameters().addValue("id", claim.id()).addValue("token", claim.token()), Boolean.class));
    }

    @Transactional
    public void cancelClaim(Claim claim) {
        jdbc.update("""
                UPDATE notification_delivery SET status='CANCELLED',last_error='POLICY_CHANGED',claim_token=NULL,
                    retryable=FALSE,next_attempt_at=NULL,version=version+1
                WHERE id=:id AND status='PROCESSING' AND claim_token=:token
                """, new MapSqlParameterSource("id", claim.id()).addValue("token", claim.token()));
    }

    @Transactional
    public boolean finish(Claim claim, NotificationSender.SendResult result) {
        var params = new MapSqlParameterSource("id", claim.id()).addValue("token", claim.token())
                .addValue("status", result.status().name()).addValue("error", result.error())
                .addValue("now", Timestamp.from(clock.instant())).addValue("maxAttempts", properties.getMaxRetries() + 1)
                .addValue("canRetry", result.retryable()).addValue("retryAt", Timestamp.from(clock.instant().plus(properties.getRetryInterval())));
        return jdbc.update("""
                UPDATE notification_delivery SET status=:status,last_error=:error,claim_token=NULL,
                    sent_at=CASE WHEN :status='SENT' THEN CAST(:now AS TIMESTAMP WITH TIME ZONE) ELSE NULL END,
                    retryable=(:canRetry AND attempt_count<:maxAttempts),
                    next_attempt_at=CASE WHEN :canRetry AND attempt_count<:maxAttempts THEN CAST(:retryAt AS TIMESTAMP WITH TIME ZONE) ELSE NULL END,
                    version=version+1
                WHERE id=:id AND status='PROCESSING' AND claim_token=:token
                """, params) == 1;
    }

    private MapSqlParameterSource parameters() {
        if (!properties.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATIONS_DISABLED", "이메일 전송이 비활성화되어 있습니다.");
        return new MapSqlParameterSource("recipient", EmailAddress.normalize(properties.getEmailTo()))
                .addValue("now", Timestamp.from(clock.instant()));
    }

    public record Claim(Long id, UUID token, NotificationSender.Message message, int attemptCount) {
        public Claim(Long id, UUID token, NotificationSender.Message message) { this(id, token, message, 1); }
    }
}
