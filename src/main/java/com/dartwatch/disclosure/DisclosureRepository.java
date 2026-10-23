package com.dartwatch.disclosure;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {
    Optional<Disclosure> findByRceptNo(String rceptNo);
    Page<Disclosure> findByCompanyIdOrderByReceiptDateDescIdDesc(Long companyId, Pageable pageable);
}
