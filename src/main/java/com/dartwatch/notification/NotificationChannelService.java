package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class NotificationChannelService {
    private final JdbcTemplate jdbc;
    private final WatchedCompanyRepository watched;
    private final NotificationTargets targets;
    private final NotificationProperties properties;
    private final Clock clock;
    public NotificationChannelService(JdbcTemplate jdbc,WatchedCompanyRepository watched,NotificationTargets targets,NotificationProperties properties,Clock clock) {
        this.jdbc=jdbc; this.watched=watched; this.targets=targets; this.properties=properties; this.clock=clock;
    }
    @Transactional(readOnly=true)
    public List<Selection> get(Long watchId) {
        validate(watchId);
        if (!watched.existsById(watchId)) throw missing();
        return Arrays.stream(NotificationChannel.values()).map(channel -> jdbc.query(
                "SELECT enabled,enabled_since FROM watched_notification_channel WHERE watched_company_id=? AND channel=?",
                (row,index)->new Selection(channel,row.getBoolean(1),row.getTimestamp(2).toInstant()),watchId,channel.name())
                .stream().findFirst().orElse(new Selection(channel,channel==NotificationChannel.EMAIL,null))).toList();
    }
    @Transactional
    public List<Selection> put(Long watchId,NotificationChannel channel,Boolean enabled) {
        validate(watchId); Assert.notNull(channel,"channel required"); Assert.notNull(enabled,"enabled required");
        var watch=watched.findForUpdate(watchId).orElseThrow(NotificationChannelService::missing);
        Instant baseline=clock.instant();
        if (channel==NotificationChannel.EMAIL && enabled && watch.getNotificationsEnabledSince()!=null
                && jdbc.queryForObject("SELECT count(*) FROM watched_notification_channel WHERE watched_company_id=? AND channel='EMAIL'",Integer.class,watchId)==0)
            baseline=watch.getNotificationsEnabledSince();
        jdbc.update("""
                INSERT INTO watched_notification_channel (watched_company_id,channel,enabled,enabled_since) VALUES (?,?,?,?)
                ON CONFLICT (watched_company_id,channel) DO UPDATE SET enabled=EXCLUDED.enabled,
                    enabled_since=CASE WHEN NOT watched_notification_channel.enabled AND EXCLUDED.enabled
                        THEN EXCLUDED.enabled_since ELSE watched_notification_channel.enabled_since END
                """,watchId,channel.name(),enabled,Timestamp.from(baseline));
        if (!enabled) jdbc.update("""
                UPDATE notification_delivery n SET status='CANCELLED',last_error='POLICY_CHANGED',retryable=FALSE,
                    next_attempt_at=NULL,version=version+1 WHERE n.channel=? AND (n.status='PENDING' OR (n.status='FAILED' AND n.retryable=TRUE))
                    AND EXISTS (SELECT 1 FROM disclosure d JOIN watched_company w ON w.company_id=d.company_id
                        WHERE d.id=n.disclosure_id AND w.id=?)
                """,channel.name(),watchId);
        return get(watchId);
    }
    public Availability availability() {
        return new Availability(properties.isEnabled(),targets.all().stream().map(target -> new ChannelState(target.channel(),target.available(),target.state())).toList());
    }
    private static void validate(Long id) { Assert.isTrue(id!=null && id>0,"Invalid watch id"); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"WATCH_NOT_FOUND","관심 기업을 찾을 수 없습니다."); }
    public record Selection(NotificationChannel channel,boolean enabled,Instant enabledSince) { }
    public record ChannelState(NotificationChannel channel,boolean available,String state) { }
    public record Availability(boolean notificationsEnabled,List<ChannelState> channels) { }
}
