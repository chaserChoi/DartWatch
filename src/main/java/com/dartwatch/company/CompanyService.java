package com.dartwatch.company;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class CompanyService {
    private final CompanyRepository companies;

    public CompanyService(CompanyRepository companies) { this.companies = companies; }

    @Transactional(readOnly = true)
    public SearchResult search(String query, int page, int size) {
        Assert.hasText(query, "query is required");
        Assert.isTrue(query.length() <= 200 && page >= 0 && size >= 1 && size <= 100, "Invalid search parameters");
        var result = companies.search(query.strip(), PageRequest.of(page, size, Sort.by("name", "id")));
        return new SearchResult(result.map(CompanyView::from).getContent(), page, size, result.getTotalElements());
    }

    public record SearchResult(List<CompanyView> items, int page, int size, long totalElements) { }
    public record CompanyView(Long id, String corpCode, String stockCode, String name) {
        public static CompanyView from(Company company) {
            return new CompanyView(company.getId(), company.getCorpCode(), company.getStockCode(), company.getName());
        }
    }
}
