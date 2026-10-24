package com.dartwatch.notification;

import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.watchlist.WatchedCompany;
import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.util.Assert;

@Entity
@Table(name = "notification_rule", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_rule_target", columnNames = {"watched_company_id", "disclosure_type", "channel"}))
public class NotificationRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "watched_company_id", nullable = false, updatable = false)
    private WatchedCompany watchedCompany;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private DisclosureType disclosureType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled = true;

    private Instant enabledSince;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected NotificationRule() {
    }

    public NotificationRule(WatchedCompany watchedCompany, DisclosureType disclosureType, NotificationChannel channel) {
        this(watchedCompany, disclosureType, channel, Instant.now());
    }

    public NotificationRule(WatchedCompany watchedCompany, DisclosureType disclosureType, NotificationChannel channel,
                            Instant enabledSince) {
        Assert.notNull(watchedCompany, "watchedCompany is required");
        Assert.notNull(disclosureType, "disclosureType is required");
        Assert.notNull(channel, "channel is required");
        this.watchedCompany = watchedCompany;
        this.disclosureType = disclosureType;
        this.channel = channel;
        this.enabledSince = enabledSince;
    }

    public void setEnabled(boolean enabled) { setEnabled(enabled, Instant.now()); }
    public void setEnabled(boolean enabled, Instant now) {
        Assert.notNull(now, "activation time is required");
        if (enabled && (!this.enabled || enabledSince == null)) enabledSince = now;
        this.enabled = enabled;
    }
    public Instant getEnabledSince() { return enabledSince; }
    public Long getId() { return id; }
    public WatchedCompany getWatchedCompany() { return watchedCompany; }
    public DisclosureType getDisclosureType() { return disclosureType; }
    public NotificationChannel getChannel() { return channel; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
}
