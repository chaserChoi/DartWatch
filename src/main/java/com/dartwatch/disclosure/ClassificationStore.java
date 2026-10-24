package com.dartwatch.disclosure;

import com.dartwatch.api.ApiException;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassificationStore {
    private final WatchedCompanyRepository watched;
    private final DisclosureRepository disclosures;
    private final ClassificationProperties properties;
    private final Clock clock;

    public ClassificationStore(WatchedCompanyRepository watched, DisclosureRepository disclosures,
                               ClassificationProperties properties, Clock clock) {
        this.watched = watched;
        this.disclosures = disclosures;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Plan prepare(Long watchId) {
        if (watchId == null || watchId <= 0) throw new IllegalArgumentException("Invalid watch id");
        var watch = watched.findById(watchId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "WATCH_NOT_FOUND", "관심 기업을 찾을 수 없습니다."));
        if (!watch.isMonitoringEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "MONITORING_DISABLED", "모니터링이 비활성화되어 있습니다.");
        }
        var targets = disclosures.findClassificationTargets(watch.getCompany().getId(),
                clock.instant().minus(properties.getRetryInterval()), PageRequest.of(0, properties.getBatchSize()));
        if (targets.isEmpty()) return null;
        var from = targets.getFirst().getReceiptDate();
        var endLimit = from.plusDays(properties.getWindowDays() - 1L);
        var selected = targets.stream().filter(disclosure -> !disclosure.getReceiptDate().isBefore(from)
                        && !disclosure.getReceiptDate().isAfter(endLimit))
                .map(disclosure -> new Target(disclosure.getId(), disclosure.getRceptNo(), disclosure.getReceiptDate())).toList();
        var through = selected.stream().map(Target::receiptDate).max(LocalDate::compareTo).orElseThrow();
        return new Plan(watchId, watch.getCompany().getCorpCode(), from, through, selected);
    }

    @Transactional
    public int apply(Plan plan, Map<String, DisclosureType> types) {
        var watch = watched.findForUpdate(plan.watchId()).orElse(null);
        if (watch == null || !watch.isMonitoringEnabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "WATCH_CHANGED", "분류 중 관심 기업 상태가 변경되었습니다.");
        }
        int updated = 0;
        for (var target : plan.targets()) {
            updated += disclosures.recordClassification(target.id(), types.get(target.rceptNo()), clock.instant());
        }
        return updated;
    }

    public record Target(Long id, String rceptNo, LocalDate receiptDate) { }
    public record Plan(Long watchId, String corpCode, LocalDate from, LocalDate through, List<Target> targets) { }
}
