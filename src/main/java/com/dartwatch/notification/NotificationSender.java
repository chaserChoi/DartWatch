package com.dartwatch.notification;

import java.time.LocalDate;

public interface NotificationSender {
    SendResult send(Message message);

    record Message(String recipient, String companyName, String reportName, String typeCode,
                   LocalDate receiptDate, String rceptNo) { }

    record SendResult(NotificationStatus status, String error, boolean retryable) {
        public SendResult {
            if (status != NotificationStatus.SENT && status != NotificationStatus.FAILED && status != NotificationStatus.UNKNOWN) {
                throw new IllegalArgumentException("Invalid SMTP result status");
            }
            if ((status == NotificationStatus.SENT) != (error == null)
                    || (error != null && !error.matches("[A-Z_]{1,80}"))
                    || (retryable && status != NotificationStatus.FAILED)) throw new IllegalArgumentException("Invalid SMTP result");
        }
        public static SendResult sent() { return new SendResult(NotificationStatus.SENT, null, false); }
        public static SendResult failed(String code, boolean retryable) { return new SendResult(NotificationStatus.FAILED, code, retryable); }
        public static SendResult unknown() { return new SendResult(NotificationStatus.UNKNOWN, "TRANSPORT_UNCERTAIN", false); }
    }
}
