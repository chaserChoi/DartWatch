package com.dartwatch.company;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class CompanyTests {
    @Test
    void emptyStockCodeIsNormalizedForUnlistedCompany() {
        assertThat(new Company("00000001", " ", "Unlisted").getStockCode()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"000001", "123456789", "ABC12345"})
    void rejectsInvalidCorpCodes(String code) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Company(code, null, "Company"));
    }

    @Test
    void corpCodeAndStockCodeKeepLeadingZeroesIndependently() {
        var company = new Company("00000001", "000002", "Listed");
        assertThat(company.getCorpCode()).isEqualTo("00000001");
        assertThat(company.getStockCode()).isEqualTo("000002");
    }
}
