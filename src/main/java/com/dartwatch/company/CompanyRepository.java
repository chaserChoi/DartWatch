package com.dartwatch.company;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByCorpCode(String corpCode);
    Optional<Company> findByStockCode(String stockCode);
    Page<Company> findByNameContainingIgnoreCase(String name, Pageable pageable);

    @Query("select c from Company c where lower(c.name) like lower(concat('%', :query, '%')) or c.stockCode = :query")
    Page<Company> search(@Param("query") String query, Pageable pageable);
}
