package com.dartwatch.watchlist;

import com.dartwatch.company.Company;
import jakarta.persistence.*;
import java.time.Instant;
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

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WatchedCompany() {
    }

    public WatchedCompany(Company company) {
        Assert.notNull(company, "company is required");
        this.company = company;
    }

    public void setMonitoringEnabled(boolean monitoringEnabled) {
        this.monitoringEnabled = monitoringEnabled;
    }

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public boolean isMonitoringEnabled() { return monitoringEnabled; }
    public Instant getCreatedAt() { return createdAt; }
}
