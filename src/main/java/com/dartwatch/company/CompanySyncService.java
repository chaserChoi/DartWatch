package com.dartwatch.company;

import com.dartwatch.api.ApiException;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CompanySyncService {
    private static final Logger log = LoggerFactory.getLogger(CompanySyncService.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final OpenDartClient client;
    private final CompanyDirectoryStore store;

    public CompanySyncService(OpenDartClient client, CompanyDirectoryStore store) {
        this.client = client;
        this.store = store;
    }

    public SyncResult synchronize(boolean refresh) {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "SYNC_BUSY", "기업 동기화가 진행 중입니다.");
        }
        try {
            if (!refresh && store.isSynchronized()) return new SyncResult(false, 0);
            var records = client.downloadCompanies();
            if (records.isEmpty()) throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
            int count = store.apply(records);
            log.info("Company directory synchronization completed: count={}", count);
            return new SyncResult(true, count);
        } catch (OpenDartException ex) {
            log.warn("Company directory synchronization failed: kind={}", ex.getKind());
            throw ex;
        } finally {
            running.set(false);
        }
    }

    public record SyncResult(boolean downloaded, int companyCount) { }
}
