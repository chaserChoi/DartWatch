package com.dartwatch.watchlist;

import com.dartwatch.company.Company;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.util.Assert;

@Entity
@Table(name = "watched_company", uniqueConstraints =
        @UniqueConstraint(name = "uk_watched_company_company", columnNames = "company_id"))
public class WatchedCompany {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(nullable = false)
    private boolean monitoringEnabled = true;

    @Column(nullable = false)
    private boolean notificationsEnabled;

    private Instant notificationsEnabledSince;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    private LocalDate initialSyncThrough;
    private LocalDate lastSuccessfulCollectionDate;
    private Instant lastCollectionAttemptAt;
    private Instant lastCollectionSucceededAt;

    @Column(length = 80)
    private String lastCollectionError;

    @Column(nullable = false)
    private int lastFetchedCount;

    @Column(nullable = false)
    private int lastInsertedCount;

    protected WatchedCompany() {
    }

    public WatchedCompany(Company company) {
        Assert.notNull(company, "company is required");
        this.company = company;
    }

    public void setMonitoringEnabled(boolean monitoringEnabled) {
        setMonitoringEnabled(monitoringEnabled, Instant.now());
    }

    public void setMonitoringEnabled(boolean monitoringEnabled, Instant now) {
        if (monitoringEnabled && !this.monitoringEnabled && notificationsEnabled) {
            notificationsEnabledSince = now;
        }
        this.monitoringEnabled = monitoringEnabled;
    }

    public void setNotificationsEnabled(boolean enabled, Instant now) {
        Assert.notNull(now, "activation time is required");
        if (enabled && (!notificationsEnabled || notificationsEnabledSince == null)) notificationsEnabledSince = now;
        notificationsEnabled = enabled;
    }

    public boolean isNotificationsEnabled() { return notificationsEnabled; }
    public Instant getNotificationsEnabledSince() { return notificationsEnabledSince; }

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public boolean isMonitoringEnabled() { return monitoringEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public LocalDate getInitialSyncThrough() { return initialSyncThrough; }
    public LocalDate getLastSuccessfulCollectionDate() { return lastSuccessfulCollectionDate; }
    public Instant getLastCollectionAttemptAt() { return lastCollectionAttemptAt; }
    public Instant getLastCollectionSucceededAt() { return lastCollectionSucceededAt; }
    public String getLastCollectionError() { return lastCollectionError; }
    public int getLastFetchedCount() { return lastFetchedCount; }
    public int getLastInsertedCount() { return lastInsertedCount; }

    public void beginCollection(LocalDate today, Instant now) {
        if (initialSyncThrough == null) initialSyncThrough = today;
        lastCollectionAttemptAt = now;
        lastCollectionError = null;
    }

    public void completeCollection(LocalDate through, Instant now, int fetched, int inserted) {
        lastSuccessfulCollectionDate = through;
        lastCollectionSucceededAt = now;
        lastCollectionError = null;
        lastFetchedCount = fetched;
        lastInsertedCount = inserted;
    }

    public void failCollection(String safeErrorCode) {
        Assert.isTrue(safeErrorCode != null && safeErrorCode.matches("[A-Z_]{1,80}"), "Invalid collection error code");
        lastCollectionError = safeErrorCode;
    }
}
