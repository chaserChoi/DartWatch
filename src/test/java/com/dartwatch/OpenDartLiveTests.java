package com.dartwatch;

import com.dartwatch.collection.DisclosureCollectionService;
import com.dartwatch.company.Company;
import com.dartwatch.company.CompanyRepository;
import com.dartwatch.disclosure.DisclosureRepository;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.WatchlistService;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.*;

// Explicit liveTest task only: one directory download and at most one disclosure-list request.
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dartwatch.collection.initial-days=7", "dartwatch.opendart.max-pages=1"
})
class OpenDartLiveTests {
    private static final OwnedPostgresSchema SCHEMA = new OwnedPostgresSchema();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }

    @AfterAll
    static void cleanup(@Autowired DataSource dataSource) throws Exception { SCHEMA.drop(dataSource); }

    @Autowired OpenDartClient client;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired WatchlistService watchlist;
    @Autowired DisclosureCollectionService collector;

    @Test
    void downloadsRealDirectoryAndStoresRealDisclosureResponseInOwnedPostgresSchema() {
        var records = client.downloadCompanies();
        assertThat(records.size()).isPositive();
        var record = records.stream().filter(company -> "005930".equals(company.stockCode())).findFirst()
                .orElseThrow(() -> new AssertionError("Live test target is absent from the directory"));
        var company = new Company(record.corpCode(), record.stockCode(), record.corpName());
        company.updateDetails(record.stockCode(), record.corpName(), record.modifyDate());
        company = companies.saveAndFlush(company);
        watchlist.register(company.getId());
        var result = collector.collectAll();
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.companies()).hasSize(1);
        assertThat(result.companies().getFirst().fetched()).isPositive();
        assertThat(disclosures.count()).isEqualTo(result.companies().getFirst().inserted());
        assertThat(disclosures.findAll()).allMatch(disclosure -> disclosure.isInitialImport());
    }
}
