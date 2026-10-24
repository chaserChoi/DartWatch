package com.dartwatch.disclosure;

import com.dartwatch.company.CompanyService.CompanyView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class DisclosureService {
    private final DisclosureRepository disclosures;

    public DisclosureService(DisclosureRepository disclosures) { this.disclosures = disclosures; }

    @Transactional(readOnly = true)
    public Disclosures list(Long companyId, String query, String type, int page, int size) {
        Assert.isTrue(companyId == null || companyId > 0, "Invalid company id");
        Assert.isTrue(page >= 0 && size >= 1 && size <= 100, "Invalid page");
        if (query != null) {
            Assert.hasText(query, "query must not be blank");
            Assert.isTrue(query.length() <= 200, "query is too long");
            query = query.strip();
        }
        DisclosureType classification = type == null ? null : DisclosureType.fromDartCode(type);
        String pattern = query == null ? null : "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = disclosures.search(companyId, query, pattern, classification,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("receiptDate"), Sort.Order.desc("rceptNo"))));
        return new Disclosures(result.map(Entry::from).getContent(), page, size, result.getTotalElements());
    }

    public record Disclosures(List<Entry> items, int page, int size, long totalElements) { }
    public record Entry(Long id, CompanyView company, String rceptNo, String reportName, String type,
                        LocalDate receiptDate, String filerName, String remarks, boolean initialImport,
                        Instant detectedAt, String dartUrl) {
        static Entry from(Disclosure disclosure) {
            return new Entry(disclosure.getId(), CompanyView.from(disclosure.getCompany()), disclosure.getRceptNo(),
                    disclosure.getReportName(), disclosure.getDisclosureType() == null ? null : disclosure.getDisclosureType().getDartCode(),
                    disclosure.getReceiptDate(), disclosure.getFilerName(), disclosure.getRemarks(), disclosure.isInitialImport(),
                    disclosure.getCollectedAt(), "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + disclosure.getRceptNo());
        }
    }
}
