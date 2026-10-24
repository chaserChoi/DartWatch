package com.dartwatch.opendart;

import com.dartwatch.company.Company;
import java.time.LocalDate;
import java.util.Objects;

public record CorpRecord(String corpCode, String corpName, String stockCode, LocalDate modifyDate) {
    public CorpRecord {
        var validated = new Company(corpCode, stockCode, corpName);
        stockCode = validated.getStockCode();
        Objects.requireNonNull(modifyDate);
    }
}
