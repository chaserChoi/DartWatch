package com.dartwatch.disclosure;

import com.dartwatch.company.Company;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.util.Assert;

@Entity
@Table(name = "disclosure", uniqueConstraints =
        @UniqueConstraint(name = "uk_disclosure_rcept_no", columnNames = "rcept_no"))
public class Disclosure {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false, updatable = false)
    private Company company;

    @Column(name = "rcept_no", nullable = false, length = 14, updatable = false)
    private String rceptNo;

    @Column(nullable = false, length = 500)
    private String reportName;

    @Column(nullable = false)
    private LocalDate receiptDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DisclosureType disclosureType;

    @Column(nullable = false, updatable = false)
    private boolean initialImport;

    @Column(nullable = false, updatable = false)
    private Instant collectedAt = Instant.now();

    @Column(length = 200)
    private String companyName;

    @Column(length = 255)
    private String filerName;

    @Column(length = 1000)
    private String remarks;

    protected Disclosure() {
    }

    public Disclosure(Company company, String rceptNo, String reportName, LocalDate receiptDate,
                      DisclosureType disclosureType, boolean initialImport) {
        Assert.notNull(company, "company is required");
        Assert.isTrue(rceptNo != null && rceptNo.matches("[0-9]{14}"), "rceptNo must contain 14 digits");
        Assert.hasText(reportName, "reportName is required");
        Assert.isTrue(reportName.length() <= 500, "reportName is too long");
        Assert.notNull(receiptDate, "receiptDate is required");
        this.company = company;
        this.rceptNo = rceptNo;
        this.reportName = reportName;
        this.receiptDate = receiptDate;
        this.disclosureType = disclosureType;
        this.initialImport = initialImport;
    }

    public Long getId() { return id; }
    public Company getCompany() { return company; }
    public String getRceptNo() { return rceptNo; }
    public String getReportName() { return reportName; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public DisclosureType getDisclosureType() { return disclosureType; }
    public boolean isInitialImport() { return initialImport; }
    public Instant getCollectedAt() { return collectedAt; }
    public String getCompanyName() { return companyName; }
    public String getFilerName() { return filerName; }
    public String getRemarks() { return remarks; }
}
