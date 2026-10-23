package com.dartwatch;

import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.notification.*;
import com.dartwatch.watchlist.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class DomainPersistenceTests {
    private static final String SCHEMA = "dw_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.datasource.hikari.schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropOwnedTestSchema(@Autowired DataSource dataSource) throws Exception {
        if (!SCHEMA.matches("dw_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unexpected test schema name");
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
        }
    }

    @Autowired CompanyRepository companies;
    @Autowired WatchedCompanyRepository watchedCompanies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationRuleRepository rules;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @Test
    void companyCodeMappingsAndMultipleUnlistedCompaniesRoundTrip() {
        var listed = companies.saveAndFlush(new Company("00000001", "000001", "Listed test"));
        companies.save(new Company("00000002", null, "Unlisted one"));
        companies.saveAndFlush(new Company("00000003", null, "Unlisted two"));
        entityManager.clear();
        assertThat(companies.findByCorpCode("00000001").orElseThrow().getStockCode()).isEqualTo("000001");
        assertThat(companies.findByStockCode("000001").orElseThrow().getId()).isEqualTo(listed.getId());
        assertThat(companies.findByNameContainingIgnoreCase("UNLISTED", PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void duplicateCorpCodeIsRejected() {
        company();
        assertThatThrownBy(() -> companies.saveAndFlush(new Company("00000001", null, "Duplicate")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_company_corp_code");
    }

    @Test
    void duplicateStockCodeIsRejected() {
        companies.saveAndFlush(new Company("00000001", "000001", "First"));
        assertThatThrownBy(() -> companies.saveAndFlush(new Company("00000002", "000001", "Second")))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_company_stock_code");
    }

    @Test
    void duplicateWatchIsRejected() {
        var company = company();
        watchedCompanies.saveAndFlush(new WatchedCompany(company));
        assertThatThrownBy(() -> watchedCompanies.saveAndFlush(new WatchedCompany(company)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_watched_company_company");
    }

    @Test
    void monitoringAndRuleActivationArePersistedIndependently() {
        var watched = watchedCompanies.saveAndFlush(new WatchedCompany(company()));
        var periodic = rules.save(new NotificationRule(watched, DisclosureType.PERIODIC, NotificationChannel.EMAIL));
        var other = rules.save(new NotificationRule(watched, DisclosureType.OTHER, NotificationChannel.EMAIL));
        other.setEnabled(false);
        watched.setMonitoringEnabled(false);
        entityManager.flush();
        entityManager.clear();
        assertThat(watchedCompanies.findByMonitoringEnabledTrue()).isEmpty();
        assertThat(watchedCompanies.findByCompanyId(watched.getCompany().getId()).orElseThrow().isMonitoringEnabled())
                .isFalse();
        assertThat(rules.findByWatchedCompanyIdAndEnabledTrue(watched.getId()))
                .extracting(NotificationRule::getId).containsExactly(periodic.getId());
    }

    @Test
    void duplicateRuleIsRejected() {
        var watched = watchedCompanies.saveAndFlush(new WatchedCompany(company()));
        rules.saveAndFlush(new NotificationRule(watched, DisclosureType.PERIODIC, NotificationChannel.EMAIL));
        assertThatThrownBy(() -> rules.saveAndFlush(
                new NotificationRule(watched, DisclosureType.PERIODIC, NotificationChannel.EMAIL)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_notification_rule_target");
    }

    @Test
    void duplicateReceiptNumberIsRejectedEvenForAnotherCompany() {
        disclosure(company(), "20261024000001", false);
        var other = companies.saveAndFlush(new Company("00000002", null, "Other company"));
        assertThatThrownBy(() -> disclosure(other, "20261024000001", false))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_disclosure_rcept_no");
    }

    @Test
    void distinctReceiptsAndUnknownDisclosureTypeArePreserved() {
        var company = company();
        var first = disclosure(company, "20261024000001", true);
        var correction = disclosures.saveAndFlush(new Disclosure(company, "20261024000002", "[Correction] Test report",
                LocalDate.of(2026, 10, 24), null, false));
        entityManager.clear();
        assertThat(disclosures.findByCompanyIdOrderByReceiptDateDescIdDesc(company.getId(), PageRequest.of(0, 10)))
                .extracting(Disclosure::getId).containsExactly(correction.getId(), first.getId());
        assertThat(disclosures.findByRceptNo(first.getRceptNo()).orElseThrow().isInitialImport()).isTrue();
        assertThat(disclosures.findByRceptNo(correction.getRceptNo()).orElseThrow().getDisclosureType()).isNull();
    }

    @Test
    void duplicateDeliveryIsRejected() {
        var disclosure = disclosure(company(), "20261024000001", false);
        deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL));
        assertThatThrownBy(() -> deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uk_notification_delivery_target");
    }

    @Test
    void failureAndRetryStatusRoundTripWithoutChangingDisclosure() {
        var disclosure = disclosure(company(), "20261024000001", false);
        var delivery = deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL));
        delivery.beginAttempt();
        delivery.markFailed("SMTP unavailable");
        deliveries.flush();
        entityManager.clear();
        var failed = deliveries.findByStatusOrderByCreatedAtAsc(NotificationStatus.FAILED, PageRequest.of(0, 10))
                .getContent().getFirst();
        assertThat(failed.getLastError()).isEqualTo("SMTP unavailable");
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        failed.beginAttempt();
        failed.markSent();
        deliveries.flush();
        entityManager.clear();
        var sent = deliveries.findByDisclosureIdAndChannel(disclosure.getId(), NotificationChannel.EMAIL).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.getAttemptCount()).isEqualTo(2);
        assertThat(sent.getSentAt()).isNotNull();
        assertThat(sent.getLastError()).isNull();
        assertThat(disclosures.findById(disclosure.getId())).isPresent();
    }

    @Test
    void staleDeliveryVersionCannotOverwriteAnotherAttempt() {
        var original = deliveries.saveAndFlush(new NotificationDelivery(
                disclosure(company(), "20261024000001", false), NotificationChannel.EMAIL));
        entityManager.detach(original);
        var current = deliveries.findById(original.getId()).orElseThrow();
        current.beginAttempt();
        deliveries.flush();
        entityManager.clear();
        original.beginAttempt();
        assertThatThrownBy(() -> deliveries.saveAndFlush(original))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void deletingWatchRemovesRulesButPreservesDisclosureAndDeliveryHistory() {
        var company = company();
        var watched = watchedCompanies.saveAndFlush(new WatchedCompany(company));
        var rule = rules.saveAndFlush(new NotificationRule(watched, DisclosureType.PERIODIC, NotificationChannel.EMAIL));
        var disclosure = disclosure(company, "20261024000001", false);
        var delivery = deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL));
        assertThat(watchedCompanies.deleteByCompanyId(company.getId())).isEqualTo(1);
        assertThat(rules.findById(rule.getId())).isEmpty();
        assertThat(disclosures.findById(disclosure.getId())).isPresent();
        assertThat(deliveries.findById(delivery.getId())).isPresent();
        assertThat(companies.findById(company.getId())).isPresent();
        watchedCompanies.saveAndFlush(new WatchedCompany(companies.findById(company.getId()).orElseThrow()));
        assertThatThrownBy(() -> deliveries.saveAndFlush(new NotificationDelivery(
                disclosures.findById(disclosure.getId()).orElseThrow(), NotificationChannel.EMAIL)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void companyWithDisclosuresCannotBeDeleted() {
        var company = company();
        disclosure(company, "20261024000001", false);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM company WHERE id = ?", company.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_disclosure_company");
    }

    @Test
    void disclosureWithDeliveryCannotBeDeleted() {
        var disclosure = disclosure(company(), "20261024000001", false);
        deliveries.saveAndFlush(new NotificationDelivery(disclosure, NotificationChannel.EMAIL));
        assertThatThrownBy(() -> jdbc.update("DELETE FROM disclosure WHERE id = ?", disclosure.getId()))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_notification_delivery_disclosure");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE company SET corp_code = '1234567' WHERE id = ?",
            "UPDATE company SET stock_code = 'invalid' WHERE id = ?",
            "UPDATE company SET name = '   ' WHERE id = ?",
            "UPDATE company SET corp_code = NULL WHERE id = ?"
    })
    void databaseRejectsInvalidCompanyValuesEvenWithoutEntityValidation(String sql) {
        var company = company();
        assertThatThrownBy(() -> jdbc.update(sql, company.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE disclosure SET rcept_no = '123' WHERE id = ?",
            "UPDATE disclosure SET company_id = -1 WHERE id = ?",
            "UPDATE disclosure SET disclosure_type = 'INVALID' WHERE id = ?",
            "UPDATE disclosure SET report_name = ' ' WHERE id = ?"
    })
    void databaseRejectsInvalidDisclosureValues(String sql) {
        var disclosure = disclosure(company(), "20261024000001", false);
        assertThatThrownBy(() -> jdbc.update(sql, disclosure.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE notification_delivery SET attempt_count = -1 WHERE id = ?",
            "UPDATE notification_delivery SET status = 'SENT' WHERE id = ?",
            "UPDATE notification_delivery SET status = 'INVALID' WHERE id = ?",
            "UPDATE notification_delivery SET channel = 'INVALID' WHERE id = ?",
            "UPDATE notification_delivery SET disclosure_id = -1 WHERE id = ?"
    })
    void databaseRejectsInvalidDeliveryValues(String sql) {
        var delivery = deliveries.saveAndFlush(new NotificationDelivery(
                disclosure(company(), "20261024000001", false), NotificationChannel.EMAIL));
        assertThatThrownBy(() -> jdbc.update(sql, delivery.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Company company() {
        return companies.saveAndFlush(new Company("00000001", null, "Test company"));
    }

    private Disclosure disclosure(Company company, String rceptNo, boolean initialImport) {
        return disclosures.saveAndFlush(new Disclosure(company, rceptNo, "Test report", LocalDate.of(2026, 10, 24),
                DisclosureType.PERIODIC, initialImport));
    }
}
