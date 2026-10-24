package com.dartwatch.watchlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

public interface WatchedCompanyRepository extends JpaRepository<WatchedCompany, Long> {
    Optional<WatchedCompany> findByCompanyId(Long companyId);
    @EntityGraph(attributePaths = "company")
    List<WatchedCompany> findByMonitoringEnabledTrue();

    @EntityGraph(attributePaths = "company")
    List<WatchedCompany> findAllByOrderByIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WatchedCompany w join fetch w.company where w.id = :id")
    Optional<WatchedCompany> findForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from WatchedCompany watched where watched.company.id = :companyId")
    int deleteByCompanyId(@Param("companyId") Long companyId);
}
