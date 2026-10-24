package com.dartwatch.notification;

import java.time.LocalDate;
import java.time.Duration;

public interface NotificationSender {
    SendResult send(Message message);
    default String channel() { return "EMAIL"; }

    record Message(String recipient, String companyName, String reportName, String typeCode,
                   LocalDate receiptDate, String rceptNo) { }

    record SendResult(NotificationStatus status, String error, boolean retryable,
                      String externalMessageId, Duration retryAfter) {
        public SendResult(NotificationStatus status, String error, boolean retryable) {
            this(status, error, retryable, null, null);
        }
        public SendResult {
            if (status != NotificationStatus.SENT && status != NotificationStatus.FAILED && status != NotificationStatus.UNKNOWN) {
                throw new IllegalArgumentException("Invalid SMTP result status");
            }
            if ((status == NotificationStatus.SENT) != (error == null)
                    || (error != null && !error.matches("[A-Z_]{1,80}"))
                    || (retryable && status != NotificationStatus.FAILED)) throw new IllegalArgumentException("Invalid SMTP result");
            if (externalMessageId != null && (status != NotificationStatus.SENT || !externalMessageId.matches("[1-9][0-9]{0,19}")))
                throw new IllegalArgumentException("Invalid external message id");
            if (retryAfter != null && (!retryable || retryAfter.isNegative() || retryAfter.isZero()))
                throw new IllegalArgumentException("Invalid retry delay");
        }
        public static SendResult sent() { return new SendResult(NotificationStatus.SENT, null, false); }
        public static SendResult failed(String code, boolean retryable) { return new SendResult(NotificationStatus.FAILED, code, retryable); }
        public static SendResult unknown() { return new SendResult(NotificationStatus.UNKNOWN, "TRANSPORT_UNCERTAIN", false); }
    }
}
