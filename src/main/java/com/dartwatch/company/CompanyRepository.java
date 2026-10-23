package com.dartwatch.company;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByCorpCode(String corpCode);
    Optional<Company> findByStockCode(String stockCode);
    Page<Company> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
