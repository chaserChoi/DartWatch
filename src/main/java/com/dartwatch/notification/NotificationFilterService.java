package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.Disclosure;
import com.dartwatch.disclosure.DisclosureRepository;
import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class NotificationFilterService {
    private final WatchedCompanyRepository watched;
    private final DisclosureRepository candidates;

    public NotificationFilterService(WatchedCompanyRepository watched, DisclosureRepository candidates) {
        this.watched = watched;
        this.candidates = candidates;
    }

    @Transactional(readOnly = true)
    public Candidates preview(Long watchId, int page, int size) {
        Assert.isTrue(watchId != null && watchId > 0 && page >= 0 && size >= 1 && size <= 100, "Invalid candidate query");
        if (!watched.existsById(watchId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WATCH_NOT_FOUND", "관심 기업을 찾을 수 없습니다.");
        }
        var types = Arrays.stream(DisclosureType.values()).filter(DisclosureType::isMvpNotificationType).toList();
        var result = candidates.findEligible(watchId, NotificationChannel.EMAIL, types,
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("collectedAt"), Sort.Order.desc("id"))));
        return new Candidates(result.map(Candidate::from).getContent(), page, size, result.getTotalElements());
    }

    public record Candidates(List<Candidate> items, int page, int size, long totalElements) { }
    public record Candidate(Long id, String rceptNo, String companyName, String reportName, String type,
                            LocalDate receiptDate, Instant detectedAt) {
        static Candidate from(Disclosure disclosure) {
            return new Candidate(disclosure.getId(), disclosure.getRceptNo(), disclosure.getCompany().getName(),
                    disclosure.getReportName(), disclosure.getDisclosureType().getDartCode(),
                    disclosure.getReceiptDate(), disclosure.getCollectedAt());
        }
    }
}
