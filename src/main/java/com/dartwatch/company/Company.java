package com.dartwatch.company;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.util.Assert;

@Entity
@Table(name = "company", uniqueConstraints = {
        @UniqueConstraint(name = "uk_company_corp_code", columnNames = "corp_code"),
        @UniqueConstraint(name = "uk_company_stock_code", columnNames = "stock_code")
})
public class Company {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8, updatable = false)
    private String corpCode;

    @Column(length = 6)
    private String stockCode;

    @Column(nullable = false, length = 200)
    private String name;

    private LocalDate dartModifiedOn;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Company() {
    }

    public Company(String corpCode, String stockCode, String name) {
        Assert.isTrue(corpCode != null && corpCode.matches("[0-9]{8}"), "corpCode must contain 8 digits");
        this.corpCode = corpCode;
        updateDetails(stockCode, name, null);
    }

    public void updateDetails(String stockCode, String name, LocalDate dartModifiedOn) {
        String normalizedStockCode = stockCode == null || stockCode.isBlank() ? null : stockCode;
        Assert.isTrue(normalizedStockCode == null || normalizedStockCode.matches("[0-9A-Z]{6}"),
                "stockCode must contain 6 uppercase letters or digits");
        Assert.hasText(name, "name is required");
        Assert.isTrue(name.length() <= 200, "name is too long");
        this.stockCode = normalizedStockCode;
        this.name = name;
        this.dartModifiedOn = dartModifiedOn;
    }

    public Long getId() { return id; }
    public String getCorpCode() { return corpCode; }
    public String getStockCode() { return stockCode; }
    public String getName() { return name; }
    public LocalDate getDartModifiedOn() { return dartModifiedOn; }
    public Instant getCreatedAt() { return createdAt; }
}
