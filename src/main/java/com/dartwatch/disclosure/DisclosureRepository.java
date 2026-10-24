package com.dartwatch.disclosure;

import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.time.LocalDate;
import java.time.Instant;
import com.dartwatch.notification.NotificationChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {
    Optional<Disclosure> findByRceptNo(String rceptNo);
    Page<Disclosure> findByCompanyIdOrderByReceiptDateDescIdDesc(Long companyId, Pageable pageable);

    @Query("""
            select d from Disclosure d where d.company.id = :companyId and d.disclosureType is null
            and (d.classificationCheckedAt is null or d.classificationCheckedAt <= :retryBefore)
            order by case when d.classificationCheckedAt is null then 0 else 1 end, d.receiptDate, d.id
            """)
    List<Disclosure> findClassificationTargets(@Param("companyId") Long companyId,
                                               @Param("retryBefore") Instant retryBefore, Pageable pageable);

    @Modifying
    @Query("""
            update Disclosure d set d.disclosureType = :type, d.classificationCheckedAt = :checkedAt
            where d.id = :id and d.disclosureType is null
            """)
    int recordClassification(@Param("id") Long id, @Param("type") DisclosureType type,
                             @Param("checkedAt") Instant checkedAt);

    @Query("""
            select d from Disclosure d where d.initialImport = false and d.disclosureType in :types
            and exists (select r.id from NotificationRule r join r.watchedCompany w
                where w.id = :watchId and w.company = d.company
                and w.monitoringEnabled = true and w.notificationsEnabled = true
                and w.notificationsEnabledSince is not null and d.collectedAt > w.notificationsEnabledSince
                and r.enabled = true and r.channel = :channel and r.disclosureType = d.disclosureType
                and r.enabledSince is not null and d.collectedAt > r.enabledSince)
            """)
    Page<Disclosure> findEligible(@Param("watchId") Long watchId, @Param("channel") NotificationChannel channel,
                                  @Param("types") List<DisclosureType> types, Pageable pageable);

    @Query("select d.rceptNo from Disclosure d where d.rceptNo in :receipts")
    List<String> findExistingReceipts(@Param("receipts") Collection<String> receipts);

    @Modifying
    @Query(value = """
            INSERT INTO disclosure (company_id, rcept_no, report_name, receipt_date, initial_import,
                collected_at, company_name, filer_name, remarks)
            VALUES (:companyId, :receipt, :reportName, :receiptDate, :initialImport,
                CURRENT_TIMESTAMP, :companyName, :filerName, :remarks)
            ON CONFLICT (rcept_no) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("companyId") Long companyId, @Param("receipt") String receipt,
                       @Param("reportName") String reportName, @Param("receiptDate") LocalDate receiptDate,
                       @Param("initialImport") boolean initialImport, @Param("companyName") String companyName,
                       @Param("filerName") String filerName, @Param("remarks") String remarks);
}
