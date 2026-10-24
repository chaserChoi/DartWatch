package com.dartwatch;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dartwatch.collection.*;
import com.dartwatch.notification.*;
import com.dartwatch.opendart.*;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static com.dartwatch.support.DartFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OperationalLoggingTests {
    @Test
    void collectionLogsCorrelatedResultsCountsAndSafeFailures() {
        var client = mock(OpenDartClient.class);
        var store = mock(CollectionStore.class);
        var day = LocalDate.of(2026, 10, 24);
        var plan = new CollectionStore.Plan(1L, 10L, "00000001", day, day, day, null);
        when(store.activeWatchIds()).thenReturn(List.of(1L, 2L));
        when(store.prepare(1L)).thenReturn(plan);
        when(client.findDisclosures("00000001", day, day)).thenReturn(List.of(disclosure("20261024000001", day),
                disclosure("20261024000002", day), disclosure("20261024000003", day)));
        when(store.complete(eq(plan), any())).thenReturn(new CollectionStore.Outcome(1L, "SUCCESS", day, day, 3, 1, null));
        when(store.prepare(2L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("password=private token=hidden"));
        var logger = (Logger) LoggerFactory.getLogger(DisclosureCollectionService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(new DisclosureCollectionService(client, store).collectAll().status()).isEqualTo("PARTIAL_FAILED");
            var events = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            String runId = events.getFirst().substring(events.getFirst().indexOf("runId=") + 6);
            assertThatCode(() -> UUID.fromString(runId)).doesNotThrowAnyException();
            assertThat(events).allMatch(event -> event.contains("runId=" + runId));
            assertThat(String.join("\n", events)).contains("corpCode=00000001", "duplicates=2", "inserted=1",
                    "durationMs=", "error=PERSISTENCE", "status=PARTIAL_FAILED").doesNotContain("private", "password", "token=hidden");
            assertThat(appender.list).allMatch(event -> event.getThrowableProxy() == null);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test
    void lostNotificationResultIsLoggedAsUncertainWithAttemptAndNoRecipientOrClaimToken() {
        var properties = new NotificationProperties();
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        var store = mock(NotificationWorkStore.class);
        var sender = mock(NotificationSender.class);
        var message = new NotificationSender.Message("recipient@example.invalid", "Company", "Report", "A",
                LocalDate.of(2026, 10, 24), "20261024000001");
        var claim = new NotificationWorkStore.Claim(11L, UUID.randomUUID(), message, 2);
        when(store.claimOne()).thenReturn(claim, (NotificationWorkStore.Claim) null);
        when(store.isStillEligible(claim)).thenReturn(true);
        when(sender.send(message)).thenReturn(NotificationSender.SendResult.sent());
        when(store.finish(claim, NotificationSender.SendResult.sent())).thenReturn(false);
        var logger = (Logger) LoggerFactory.getLogger(NotificationDispatchService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var dispatcher = new NotificationDispatchService(properties, store, sender, mock(WatchedCompanyRepository.class));
            assertThat(dispatcher.dispatch().unknown()).isEqualTo(1);
            String events = String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
            assertThat(events).contains("runId=", "deliveryId=11", "rceptNo=20261024000001", "channel=EMAIL",
                    "status=UNKNOWN", "smtpResult=SENT", "recorded=false", "attempt=2", "retryCount=1", "durationMs=")
                    .doesNotContain(message.recipient(), properties.getMailFrom(), claim.token().toString());
            assertThat(appender.list).filteredOn(event -> event.getFormattedMessage().contains("status=UNKNOWN"))
                    .allMatch(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
