package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.DisclosureType;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class NotificationWorkStore {
    private static final String ELIGIBLE = """
            d.initial_import=FALSE
            AND d.disclosure_type IN ('PERIODIC','MATERIAL_EVENT','ISSUANCE','SHAREHOLDING','OTHER')
            AND EXISTS (SELECT 1 FROM notification_targets g WHERE g.channel=:channel AND g.recipient=:recipient
                AND g.enabled=TRUE AND d.collected_at>g.enabled_since)
            AND EXISTS (SELECT 1 FROM watched_company w JOIN notification_rule r ON r.watched_company_id=w.id
                WHERE w.company_id=d.company_id AND w.monitoring_enabled=TRUE AND w.notifications_enabled=TRUE
                AND d.collected_at>w.notifications_enabled_since AND r.enabled=TRUE
                AND r.channel='EMAIL' AND r.disclosure_type=d.disclosure_type AND d.collected_at>r.enabled_since
                AND ((:channel='EMAIL' AND NOT EXISTS (SELECT 1 FROM watched_notification_channel s
                        WHERE s.watched_company_id=w.id AND s.channel=:channel))
                    OR EXISTS (SELECT 1 FROM watched_notification_channel s WHERE s.watched_company_id=w.id
                        AND s.channel=:channel AND s.enabled=TRUE AND d.collected_at>s.enabled_since)))
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationProperties properties;
    private final NotificationTargets targets;
    private final Clock clock;
    public NotificationWorkStore(NamedParameterJdbcTemplate jdbc, NotificationProperties properties, NotificationTargets targets, Clock clock) {
        this.jdbc=jdbc; this.properties=properties; this.targets=targets; this.clock=clock;
    }
    @Transactional
    public void synchronizeConfiguration() {
        var now=new MapSqlParameterSource();
        if (!properties.isEnabled()) {
            jdbc.update("UPDATE notification_email_settings SET enabled=FALSE WHERE enabled=TRUE", now);
            jdbc.update("UPDATE notification_channel_settings SET enabled=FALSE WHERE enabled=TRUE", now);
            jdbc.update("""
                    UPDATE notification_delivery SET status='CANCELLED',last_error='NOTIFICATIONS_DISABLED',
                        retryable=FALSE,next_attempt_at=NULL,version=version+1
                    WHERE recipient IS NOT NULL AND (status='PENDING' OR (status='FAILED' AND retryable=TRUE))
                    """, now);
            return;
        }
        for (var target : targets.all()) {
            var params=targetParameters(target.channel(), target.recipient());
            String table=table(target.channel());
            String channel=target.channel()==NotificationChannel.EMAIL ? "" : " AND channel=:channel";
            jdbc.update("UPDATE " + table + " SET enabled=FALSE WHERE enabled=TRUE" + channel
                    + (target.available() ? " AND recipient<>:recipient" : ""), params);
            if (!target.available()) continue;
            if (target.channel()==NotificationChannel.EMAIL) {
                jdbc.update("""
                        INSERT INTO notification_email_settings (recipient,enabled,enabled_since) VALUES (:recipient,TRUE,:now)
                        ON CONFLICT (recipient) DO UPDATE SET enabled=TRUE,
                            enabled_since=CASE WHEN notification_email_settings.enabled THEN notification_email_settings.enabled_since ELSE EXCLUDED.enabled_since END
                        """, params);
            } else {
                jdbc.update("""
                        INSERT INTO notification_channel_settings (channel,recipient,enabled,enabled_since) VALUES (:channel,:recipient,TRUE,:now)
                        ON CONFLICT (channel,recipient) DO UPDATE SET enabled=TRUE,
                            enabled_since=CASE WHEN notification_channel_settings.enabled THEN notification_channel_settings.enabled_since ELSE EXCLUDED.enabled_since END
                        """, params);
            }
        }
    }
    @Transactional
    public int enqueue(Long watchId) {
        if (!properties.isEnabled()) throw disabled();
        int count=0;
        for (var target : targets.all()) {
            if (!target.available()) continue;
            var params=targetParameters(target.channel(),target.recipient()).addValue("watchId",watchId).addValue("batchSize",properties.getBatchSize());
            count+=jdbc.update("""
                    INSERT INTO notification_delivery (disclosure_id,channel,recipient,status,attempt_count,created_at,version,next_attempt_at,retryable)
                    SELECT d.id,:channel,:recipient,'PENDING',0,:now,0,:now,FALSE FROM disclosure d
                    WHERE EXISTS (SELECT 1 FROM watched_company w WHERE w.id=:watchId AND w.company_id=d.company_id)
                    AND NOT EXISTS (SELECT 1 FROM notification_delivery n WHERE n.disclosure_id=d.id AND n.channel=:channel
                        AND (n.recipient=:recipient OR n.recipient IS NULL)) AND
                    """ + ELIGIBLE + " ORDER BY d.collected_at,d.id LIMIT :batchSize ON CONFLICT DO NOTHING",params);
        }
        return count;
    }
    @Transactional
    public int recoverStale() {
        return jdbc.update("""
                UPDATE notification_delivery SET status='UNKNOWN',last_error='RECOVERED_IN_FLIGHT',claim_token=NULL,
                    retryable=FALSE,next_attempt_at=NULL,version=version+1
                WHERE status='PROCESSING' AND last_attempt_at<=:staleBefore
                """,new MapSqlParameterSource("staleBefore",Timestamp.from(clock.instant().minus(properties.getProcessingTimeout()))));
    }
    @Transactional
    public int stopExhaustedRetries() {
        return jdbc.update("""
                UPDATE notification_delivery SET retryable=FALSE,next_attempt_at=NULL,version=version+1
                WHERE status='FAILED' AND retryable=TRUE AND attempt_count>=:maxAttempts
                """,new MapSqlParameterSource("maxAttempts",properties.getMaxRetries()+1));
    }
    @Transactional
    public int cancelIneligible() {
        // Bind each row's immutable channel/target, preserving legacy rows without a known recipient.
        String eligible=ELIGIBLE.replace(":channel","n.channel").replace(":recipient","n.recipient");
        return jdbc.update("""
                UPDATE notification_delivery n SET status='CANCELLED',last_error='POLICY_CHANGED',retryable=FALSE,
                    next_attempt_at=NULL,version=version+1
                WHERE n.recipient IS NOT NULL AND (n.status='PENDING' OR (n.status='FAILED' AND n.retryable=TRUE))
                AND NOT EXISTS (SELECT 1 FROM disclosure d WHERE d.id=n.disclosure_id AND
                """+eligible+")",parameters());
    }
    @Transactional
    public Claim claimOne() {
        String eligible=ELIGIBLE.replace(":channel","n.channel").replace(":recipient","n.recipient");
        var params=parameters().addValue("maxAttempts",properties.getMaxRetries()+1);
        var ids=jdbc.queryForList("""
                SELECT n.id FROM notification_delivery n JOIN disclosure d ON d.id=n.disclosure_id
                JOIN notification_targets g ON g.channel=n.channel AND g.recipient=n.recipient
                WHERE n.next_attempt_at<=:now AND (g.blocked_until IS NULL OR g.blocked_until<=:now)
                AND n.attempt_count<:maxAttempts AND (n.status='PENDING' OR (n.status='FAILED' AND n.retryable=TRUE)) AND
                """+eligible+" ORDER BY n.next_attempt_at,n.id LIMIT 1 FOR UPDATE OF n SKIP LOCKED",params,Long.class);
        if (ids.isEmpty()) return null;
        UUID token=UUID.randomUUID(); params.addValue("id",ids.getFirst()).addValue("token",token);
        jdbc.update("""
                UPDATE notification_delivery SET status='PROCESSING',attempt_count=attempt_count+1,last_attempt_at=:now,
                    claim_token=:token,last_error=NULL,next_attempt_at=NULL,retryable=FALSE,version=version+1 WHERE id=:id
                """,params);
        return jdbc.queryForObject("""
                SELECT n.id,n.channel,n.recipient,n.attempt_count,coalesce(d.company_name,c.name) AS company_name,
                    d.report_name,d.disclosure_type,d.receipt_date,d.rcept_no FROM notification_delivery n
                    JOIN disclosure d ON d.id=n.disclosure_id JOIN company c ON c.id=d.company_id WHERE n.id=:id
                """,params,(row,index)->new Claim(row.getLong("id"),token,new NotificationSender.Message(row.getString("recipient"),
                    row.getString("company_name"),row.getString("report_name"),DisclosureType.valueOf(row.getString("disclosure_type")).getDartCode(),
                    row.getDate("receipt_date").toLocalDate(),row.getString("rcept_no")),row.getInt("attempt_count"),
                    NotificationChannel.valueOf(row.getString("channel"))));
    }
    @Transactional(readOnly=true)
    public boolean isStillEligible(Claim claim) {
        var target=targets.get(claim.channel());
        if (!properties.isEnabled() || !target.available() || !target.recipient().equals(claim.message().recipient())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_delivery n JOIN disclosure d ON d.id=n.disclosure_id
                    WHERE n.id=:id AND n.status='PROCESSING' AND n.claim_token=:token AND n.recipient=:recipient AND n.channel=:channel AND
                """+ELIGIBLE+")",targetParameters(claim.channel(),claim.message().recipient()).addValue("id",claim.id()).addValue("token",claim.token()),Boolean.class));
    }
    @Transactional
    public void cancelClaim(Claim claim) {
        jdbc.update("""
                UPDATE notification_delivery SET status='CANCELLED',last_error='POLICY_CHANGED',claim_token=NULL,
                    retryable=FALSE,next_attempt_at=NULL,version=version+1 WHERE id=:id AND status='PROCESSING' AND claim_token=:token
                """,new MapSqlParameterSource("id",claim.id()).addValue("token",claim.token()));
    }
    @Transactional
    public boolean finish(Claim claim,NotificationSender.SendResult result) {
        Duration delay=properties.getRetryInterval();
        if (result.retryAfter()!=null && result.retryAfter().compareTo(delay)>0) delay=result.retryAfter();
        var params=targetParameters(claim.channel(),claim.message().recipient()).addValue("id",claim.id()).addValue("token",claim.token())
                .addValue("status",result.status().name()).addValue("error",result.error()).addValue("externalId",result.externalMessageId())
                .addValue("maxAttempts",properties.getMaxRetries()+1).addValue("canRetry",result.retryable())
                .addValue("retryAt",Timestamp.from(clock.instant().plus(delay)));
        boolean saved=jdbc.update("""
                UPDATE notification_delivery SET status=:status,last_error=:error,claim_token=NULL,external_message_id=:externalId,
                    sent_at=CASE WHEN :status='SENT' THEN CAST(:now AS TIMESTAMP WITH TIME ZONE) ELSE NULL END,
                    retryable=(:canRetry AND attempt_count<:maxAttempts),
                    next_attempt_at=CASE WHEN :canRetry AND attempt_count<:maxAttempts THEN CAST(:retryAt AS TIMESTAMP WITH TIME ZONE) ELSE NULL END,
                    version=version+1 WHERE id=:id AND status='PROCESSING' AND claim_token=:token
                """,params)==1;
        if (saved && (result.retryAfter()!=null || permanentChannelError(result.error()))) {
            jdbc.update("UPDATE "+table(claim.channel())+" SET blocked_until=greatest(blocked_until,CAST(:retryAt AS TIMESTAMP WITH TIME ZONE))"
                    +" WHERE recipient=:recipient"+(claim.channel()==NotificationChannel.EMAIL?"":" AND channel=:channel"),params);
        }
        return saved;
    }
    private static boolean permanentChannelError(String error) {
        return error!=null && java.util.Set.of("AUTHENTICATION","CONFIGURATION","DISCORD_AUTHENTICATION","DISCORD_WEBHOOK_NOT_FOUND",
                "KAKAO_PERMISSION","KAKAO_RECONNECT_REQUIRED","KAKAO_LINK_CONFIGURATION").contains(error);
    }
    private static String table(NotificationChannel channel) {
        return channel==NotificationChannel.EMAIL?"notification_email_settings":"notification_channel_settings";
    }
    private MapSqlParameterSource parameters() { return new MapSqlParameterSource("now",Timestamp.from(clock.instant())); }
    private MapSqlParameterSource targetParameters(NotificationChannel channel,String recipient) {
        return parameters().addValue("channel",channel.name()).addValue("recipient",recipient);
    }
    private static ApiException disabled() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"NOTIFICATIONS_DISABLED","알림 전송이 비활성화되어 있습니다."); }
    public record Claim(Long id,UUID token,NotificationSender.Message message,int attemptCount,NotificationChannel channel) {
        public Claim(Long id,UUID token,NotificationSender.Message message,int attemptCount) { this(id,token,message,attemptCount,NotificationChannel.EMAIL); }
        public Claim(Long id,UUID token,NotificationSender.Message message) { this(id,token,message,1); }
    }
}
