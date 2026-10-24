package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@EnableConfigurationProperties({NotificationProperties.class,MailProperties.class})
public class NotificationDispatchService {
    private static final Logger log=LoggerFactory.getLogger(NotificationDispatchService.class);
    private final NotificationProperties properties;
    private final NotificationWorkStore store;
    private final List<NotificationSender> senders;
    private final WatchedCompanyRepository watched;
    @Autowired
    public NotificationDispatchService(NotificationProperties properties,NotificationWorkStore store,List<NotificationSender> senders,WatchedCompanyRepository watched) {
        this.properties=properties; this.store=store; this.watched=watched;
        this.senders=List.copyOf(senders);
    }
    public NotificationDispatchService(NotificationProperties properties,NotificationWorkStore store,NotificationSender sender,WatchedCompanyRepository watched) {
        this(properties,store,List.of(sender),watched);
    }
    @EventListener(ApplicationReadyEvent.class) @Order(0)
    public void initialize() { store.synchronizeConfiguration(); }
    public int enqueue(Long watchId) {
        requireEnabled();
        Assert.isTrue(watchId!=null && watchId>0,"Invalid watch id");
        if (!watched.existsById(watchId)) throw new ApiException(HttpStatus.NOT_FOUND,"WATCH_NOT_FOUND","관심 기업을 찾을 수 없습니다.");
        return store.enqueue(watchId);
    }
    public DispatchResult dispatch() {
        requireEnabled();
        String runId=UUID.randomUUID().toString(); long started=System.nanoTime();
        log.info("Notification batch started: runId={}",runId);
        try { return dispatchBatch(runId); }
        catch (RuntimeException ex) {
            log.warn("Notification batch failed: runId={}, error={}",runId,ex instanceof DataAccessException?"PERSISTENCE":"INTERNAL");
            throw ex;
        } finally { log.info("Notification batch finished: runId={}, durationMs={}",runId,elapsed(started)); }
    }
    private DispatchResult dispatchBatch(String runId) {
        int recovered=store.recoverStale(); store.stopExhaustedRetries();
        int sent=0,failed=0,unknown=0,cancelled=store.cancelIneligible();
        for (int index=0; index<properties.getBatchSize(); index++) {
            if (!properties.isEnabled()) break;
            var claim=store.claimOne();
            if (claim==null) break;
            long started=System.nanoTime();
            log.info("Notification started: runId={}, deliveryId={}, rceptNo={}, channel={}, attempt={}, retryCount={}",
                    runId,claim.id(),claim.message().rceptNo(),claim.channel(),claim.attemptCount(),Math.max(0,claim.attemptCount()-1));
            try {
                if (!store.isStillEligible(claim) || !properties.isEnabled()) {
                    store.cancelClaim(claim); cancelled++;
                    log.info("Notification finished: runId={}, deliveryId={}, channel={}, status=CANCELLED, error=POLICY_CHANGED, durationMs={}",
                            runId,claim.id(),claim.channel(),elapsed(started));
                    continue;
                }
            } catch (RuntimeException ex) {
                log.warn("Notification paused before SMTP: runId={}, deliveryId={}, channel={}, error=PRE_SEND_CHECK, durationMs={}",
                        runId,claim.id(),claim.channel(),elapsed(started));
                throw ex;
            }
            NotificationSender.SendResult result;
            try {
                var sender=senderFor(claim.channel());
                result=sender==null?NotificationSender.SendResult.failed("CONFIGURATION",false):sender.send(claim.message());
                if (result==null) result=NotificationSender.SendResult.unknown();
            } catch (RuntimeException ex) { result=NotificationSender.SendResult.unknown(); }
            boolean recorded;
            try { recorded=store.finish(claim,result); }
            catch (RuntimeException ex) {
                // The external service may have accepted it. Never restore PENDING after a lost result.
                boolean quarantined=false;
                try { quarantined=store.finish(claim,NotificationSender.SendResult.unknown()); } catch (RuntimeException unavailable) { }
                log.warn("Notification finished: runId={}, deliveryId={}, channel={}, status=UNKNOWN, smtpResult={}, recorded=false, error=RESULT_PERSISTENCE, durationMs={}",
                        runId,claim.id(),claim.channel(),result.status(),elapsed(started));
                unknown++;
                if (!quarantined) break; // A shared DB outage cannot safely record any channel's outcome.
                continue;
            }
            if (!recorded) unknown++;
            else switch(result.status()) { case SENT -> sent++; case FAILED -> failed++; default -> unknown++; }
            var event=recorded && result.status()==NotificationStatus.SENT?log.atInfo():log.atWarn();
            event.log("Notification finished: runId={}, deliveryId={}, rceptNo={}, channel={}, status={}, smtpResult={}, recorded={}, attempt={}, retryCount={}, error={}, durationMs={}",
                    runId,claim.id(),claim.message().rceptNo(),claim.channel(),recorded?result.status():NotificationStatus.UNKNOWN,
                    result.status(),recorded,claim.attemptCount(),Math.max(0,claim.attemptCount()-1),recorded?result.error():"CLAIM_LOST",elapsed(started));
        }
        log.info("Notification batch result: runId={}, sent={}, failed={}, unknown={}, cancelled={}, recoveredUnknown={}",runId,sent,failed,unknown,cancelled,recovered);
        return new DispatchResult(sent,failed,unknown,cancelled,recovered);
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started); }
    private NotificationSender senderFor(NotificationChannel channel) {
        NotificationSender result=null;
        for (var sender:senders) {
            String name=sender.channel();
            if (channel!=(name==null?NotificationChannel.EMAIL:NotificationChannel.valueOf(name))) continue;
            Assert.state(result==null,"Duplicate notification sender");
            result=sender;
        }
        return result;
    }
    private void requireEnabled() {
        store.synchronizeConfiguration();
        if (!properties.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"NOTIFICATIONS_DISABLED","알림 전송이 비활성화되어 있습니다.");
    }
    public record DispatchResult(int sent,int failed,int unknown,int cancelled,int recoveredUnknown) { }
}
