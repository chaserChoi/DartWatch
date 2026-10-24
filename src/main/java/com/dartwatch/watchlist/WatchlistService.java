package com.dartwatch.watchlist;

import com.dartwatch.api.ApiException;
import com.dartwatch.company.CompanyRepository;
import com.dartwatch.company.CompanyService.CompanyView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class WatchlistService {
    private final CompanyRepository companies;
    private final WatchedCompanyRepository watched;

    public WatchlistService(CompanyRepository companies, WatchedCompanyRepository watched) {
        this.companies = companies;
        this.watched = watched;
    }

    @Transactional
    public WatchView register(Long companyId) {
        Assert.isTrue(companyId != null && companyId > 0, "Invalid companyId");
        var company = companies.findById(companyId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "COMPANY_NOT_FOUND", "기업을 찾을 수 없습니다."));
        if (watched.findByCompanyId(companyId).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_WATCHED", "이미 관심 기업으로 등록되어 있습니다.");
        }
        return WatchView.from(watched.saveAndFlush(new WatchedCompany(company)));
    }

    @Transactional(readOnly = true)
    public List<WatchView> list() {
        return watched.findAllByOrderByIdAsc().stream().map(WatchView::from).toList();
    }

    @Transactional
    public WatchView setMonitoring(Long id, Boolean enabled) {
        Assert.isTrue(id > 0 && enabled != null, "Invalid monitoring request");
        var watch = watched.findForUpdate(id).orElseThrow(WatchlistService::notFound);
        watch.setMonitoringEnabled(enabled);
        return WatchView.from(watch);
    }

    @Transactional
    public void delete(Long id) {
        Assert.isTrue(id > 0, "Invalid watch id");
        var watch = watched.findForUpdate(id).orElseThrow(WatchlistService::notFound);
        watched.deleteByCompanyId(watch.getCompany().getId());
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WATCH_NOT_FOUND", "관심 기업을 찾을 수 없습니다.");
    }

    public record WatchView(Long id, CompanyView company, boolean monitoringEnabled, LocalDate initialSyncThrough,
                            LocalDate lastSuccessfulCollectionDate, Instant lastCollectionAttemptAt,
                            Instant lastCollectionSucceededAt, String lastCollectionError,
                            int lastFetchedCount, int lastInsertedCount) {
        static WatchView from(WatchedCompany watch) {
            return new WatchView(watch.getId(), CompanyView.from(watch.getCompany()), watch.isMonitoringEnabled(),
                    watch.getInitialSyncThrough(), watch.getLastSuccessfulCollectionDate(), watch.getLastCollectionAttemptAt(),
                    watch.getLastCollectionSucceededAt(), watch.getLastCollectionError(),
                    watch.getLastFetchedCount(), watch.getLastInsertedCount());
        }
    }
}
