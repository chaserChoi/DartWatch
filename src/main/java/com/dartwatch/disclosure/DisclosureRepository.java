package com.dartwatch.disclosure;

import java.util.Optional;
import java.util.List;
import java.util.Collection;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {
    Optional<Disclosure> findByRceptNo(String rceptNo);
    Page<Disclosure> findByCompanyIdOrderByReceiptDateDescIdDesc(Long companyId, Pageable pageable);

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
