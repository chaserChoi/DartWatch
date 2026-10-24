package com.dartwatch.disclosure;

import com.dartwatch.api.ApiException;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@EnableConfigurationProperties(ClassificationProperties.class)
public class DisclosureClassificationService {
    private static final Logger log = LoggerFactory.getLogger(DisclosureClassificationService.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final OpenDartClient client;
    private final ClassificationStore store;

    public DisclosureClassificationService(OpenDartClient client, ClassificationStore store) {
        this.client = client;
        this.store = store;
    }

    public Result classify(Long watchId) {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "CLASSIFICATION_BUSY", "공시 분류가 진행 중입니다.");
        }
        try {
            var plan = store.prepare(watchId);
            if (plan == null) return new Result(0, 0, 0, 0);
            var targets = plan.targets().stream().collect(Collectors.toMap(ClassificationStore.Target::rceptNo, target -> target));
            Map<String, DisclosureType> classified = new HashMap<>();
            int queriedTypes = 0;
            for (var type : DisclosureType.values()) {
                if (!type.isMvpNotificationType()) continue;
                queriedTypes++;
                var records = client.findDisclosures(plan.corpCode(), plan.from(), plan.through(), type);
                for (var record : records) {
                    record.validate(plan.corpCode(), plan.from(), plan.through());
                    var target = targets.get(record.rceptNo());
                    if (target == null) continue; // Never insert additional disclosures from a type lookup.
                    if (!target.receiptDate().equals(record.parsedReceiptDate())) {
                        throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
                    }
                    var previous = classified.putIfAbsent(record.rceptNo(), type);
                    if (previous != null && previous != type) {
                        throw new OpenDartException(OpenDartException.Kind.INVALID_RESPONSE);
                    }
                }
                if (classified.size() == targets.size()) break;
            }
            int updated = store.apply(plan, classified);
            log.info("Disclosure classification completed: watchId={}, checked={}, matched={}, queriedTypes={}",
                    watchId, updated, classified.size(), queriedTypes);
            return new Result(updated, classified.size(), targets.size() - classified.size(), queriedTypes);
        } catch (OpenDartException ex) {
            log.warn("Disclosure classification failed: watchId={}, kind={}", watchId, ex.getKind());
            throw ex;
        } finally {
            running.set(false);
        }
    }

    public record Result(int checked, int classified, int unclassified, int queriedTypes) { }
}
