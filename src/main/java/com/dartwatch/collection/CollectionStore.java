package com.dartwatch.collection;

import com.dartwatch.disclosure.DisclosureRepository;
import com.dartwatch.opendart.DartDisclosure;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionStore {
    private final WatchedCompanyRepository watched;
    private final DisclosureRepository disclosures;
    private final CollectionProperties properties;
    private final Clock clock;

    public CollectionStore(WatchedCompanyRepository watched, DisclosureRepository disclosures,
                           CollectionProperties properties, Clock clock) {
        this.watched = watched;
        this.disclosures = disclosures;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Long> activeWatchIds() {
        return watched.findByMonitoringEnabledTrue().stream().map(watch -> watch.getId()).toList();
    }

    @Transactional
    public Plan prepare(Long watchId) {
        var watch = watched.findForUpdate(watchId).orElse(null);
        if (watch == null || !watch.isMonitoringEnabled()) return null;
        var today = LocalDate.now(clock);
        watch.beginCollection(today, clock.instant());
        var previous = watch.getLastSuccessfulCollectionDate();
        var through = previous == null ? watch.getInitialSyncThrough() : today;
        var from = previous == null ? through.minusDays(properties.getInitialDays() - 1L)
                : previous.minusDays(properties.getOverlapDays());
        if (through.isBefore(from) || (previous != null && through.isBefore(previous))) {
            throw new IllegalStateException("Collection clock moved backwards");
        }
        return new Plan(watchId, watch.getCompany().getId(), watch.getCompany().getCorpCode(), from, through,
                watch.getInitialSyncThrough(), previous);
    }

    @Transactional
    public Outcome complete(Plan plan, List<DartDisclosure> records) {
        var watch = watched.findForUpdate(plan.watchId()).orElse(null);
        if (watch == null || !watch.isMonitoringEnabled()
                || !Objects.equals(watch.getInitialSyncThrough(), plan.initialThrough())
                || !Objects.equals(watch.getLastSuccessfulCollectionDate(), plan.previousSuccess())) {
            return Outcome.skipped(plan.watchId());
        }
        var receipts = records.stream().map(DartDisclosure::rceptNo).toList();
        var existing = new HashSet<String>();
        for (int offset = 0; offset < receipts.size(); offset += 1000) {
            existing.addAll(disclosures.findExistingReceipts(receipts.subList(offset, Math.min(offset + 1000, receipts.size()))));
        }
        int inserted = 0;
        for (var record : records) {
            record.validate(plan.corpCode(), plan.from(), plan.through());
            if (existing.contains(record.rceptNo())) continue;
            inserted += disclosures.insertIfAbsent(plan.companyId(), record.rceptNo(), record.reportName(),
                    record.parsedReceiptDate(), !record.parsedReceiptDate().isAfter(plan.initialThrough()),
                    record.corpName(), record.filerName(), record.remarks());
            existing.add(record.rceptNo());
        }
        watch.completeCollection(plan.through(), clock.instant(), records.size(), inserted);
        return new Outcome(plan.watchId(), "SUCCESS", plan.from(), plan.through(), records.size(), inserted, null);
    }

    @Transactional
    public void fail(Long watchId, String safeErrorCode) {
        watched.findForUpdate(watchId).ifPresent(watch -> watch.failCollection(safeErrorCode));
    }

    public record Plan(Long watchId, Long companyId, String corpCode, LocalDate from, LocalDate through,
                       LocalDate initialThrough, LocalDate previousSuccess) { }

    public record Outcome(Long watchId, String status, LocalDate from, LocalDate through,
                          int fetched, int inserted, String error) {
        static Outcome skipped(Long id) { return new Outcome(id, "SKIPPED", null, null, 0, 0, null); }
    }
}
