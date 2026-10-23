package com.dartwatch.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface WatchedCompanyRepository extends JpaRepository<WatchedCompany, Long> {
    Optional<WatchedCompany> findByCompanyId(Long companyId);
    List<WatchedCompany> findByMonitoringEnabledTrue();

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WatchedCompany watched where watched.company.id = :companyId")
    int deleteByCompanyId(@Param("companyId") Long companyId);
}
