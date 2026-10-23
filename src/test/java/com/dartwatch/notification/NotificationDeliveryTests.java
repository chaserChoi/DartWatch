package com.dartwatch.notification;

import com.dartwatch.company.Company;
import com.dartwatch.disclosure.Disclosure;
import com.dartwatch.disclosure.DisclosureType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class NotificationDeliveryTests {
    @Test
    void failedDeliveryCanRetryAndSucceed() {
        var delivery = delivery(false);
        delivery.beginAttempt();
        delivery.markFailed("SMTP unavailable");
        assertThat(delivery.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastError()).isEqualTo("SMTP unavailable");
        assertThat(delivery.getLastAttemptAt()).isNotNull();
        assertThat(delivery.getSentAt()).isNull();

        delivery.beginAttempt();
        delivery.markSent();
        assertThat(delivery.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getLastError()).isNull();
        assertThat(delivery.getSentAt()).isNotNull();
    }

    @Test
    void sentDeliveryCannotBeRetriedOrOverwritten() {
        var delivery = delivery(false);
        delivery.beginAttempt();
        delivery.markSent();
        assertThatIllegalStateException().isThrownBy(delivery::beginAttempt);
        assertThatIllegalStateException().isThrownBy(() -> delivery.markFailed("late result"));
        assertThatIllegalStateException().isThrownBy(delivery::markSent);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void pendingDeliveryCannotCompleteWithoutAnAttempt() {
        var delivery = delivery(false);
        assertThatIllegalStateException().isThrownBy(delivery::markSent);
        assertThatIllegalStateException().isThrownBy(() -> delivery.markFailed("failure"));
        assertThat(delivery.getStatus()).isEqualTo(NotificationStatus.PENDING);
    }

    @Test
    void sendingDeliveryCannotStartAnotherAttempt() {
        var delivery = delivery(false);
        delivery.beginAttempt();
        assertThatIllegalStateException().isThrownBy(delivery::beginAttempt);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void historicalDisclosureCannotCreateDelivery() {
        assertThatIllegalArgumentException().isThrownBy(() -> delivery(true))
                .withMessageContaining("initial imports");
    }

    @Test
    void failureReasonIsRequiredAndBoundedForPersistence() {
        var delivery = delivery(false);
        delivery.beginAttempt();
        assertThatIllegalArgumentException().isThrownBy(() -> delivery.markFailed(" "));
        assertThat(delivery.getStatus()).isEqualTo(NotificationStatus.SENDING);
        delivery.markFailed("x".repeat(2100));
        assertThat(delivery.getLastError()).hasSize(2000);
    }

    @Test
    void leadingWhitespaceCannotTruncateAwayTheEntireFailureReason() {
        var delivery = delivery(false);
        delivery.beginAttempt();
        delivery.markFailed(" ".repeat(2100) + "SMTP unavailable");
        assertThat(delivery.getLastError()).isEqualTo("SMTP unavailable");
    }

    private NotificationDelivery delivery(boolean initialImport) {
        var company = new Company("00000001", null, "Test company");
        var disclosure = new Disclosure(company, "20261024000001", "Test report", LocalDate.of(2026, 10, 24),
                DisclosureType.PERIODIC, initialImport);
        return new NotificationDelivery(disclosure, NotificationChannel.EMAIL);
    }
}
