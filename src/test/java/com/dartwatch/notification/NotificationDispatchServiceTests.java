package com.dartwatch.notification;

import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationDispatchServiceTests {
    @Test
    void persistenceFailureAfterSmtpAcceptanceBecomesUncertainWithoutResending() {
        var properties = new NotificationProperties();
        properties.setEnabled(true);
        properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid");
        var store = mock(NotificationWorkStore.class);
        var sender = mock(NotificationSender.class);
        var claim = new NotificationWorkStore.Claim(1L, UUID.randomUUID(), EmailNotificationSenderTests.message());
        when(store.claimOne()).thenReturn(claim);
        when(store.isStillEligible(claim)).thenReturn(true);
        when(sender.send(claim.message())).thenReturn(NotificationSender.SendResult.sent());
        when(store.finish(claim, NotificationSender.SendResult.sent())).thenThrow(new IllegalStateException("private diagnostic"));
        var service = new NotificationDispatchService(properties, store, sender, mock(WatchedCompanyRepository.class));
        assertThat(service.dispatch().unknown()).isEqualTo(1);
        verify(store).finish(claim, NotificationSender.SendResult.unknown());
        verify(sender, times(1)).send(any());
        verify(store, times(1)).claimOne();
    }
}
