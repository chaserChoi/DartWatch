package com.dartwatch.notification;

import com.dartwatch.disclosure.Disclosure;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.springframework.util.Assert;

@Entity
@Table(name = "notification_delivery", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_delivery_target", columnNames = {"disclosure_id", "channel", "recipient"}))
public class NotificationDelivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "disclosure_id", nullable = false, updatable = false)
    private Disclosure disclosure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, updatable = false)
    private NotificationChannel channel;

    @Column(length = 320, updatable = false)
    private String recipient;

    @Column(length = 20)
    private String externalMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(nullable = false)
    private int attemptCount;

    private Instant lastAttemptAt;

    @Column(length = 2000)
    private String lastError;

    private Instant sentAt;

    private Instant nextAttemptAt;

    @Column(nullable = false)
    private boolean retryable;

    private UUID claimToken;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected NotificationDelivery() {
    }

    public NotificationDelivery(Disclosure disclosure, NotificationChannel channel) {
        this(disclosure, channel, null);
    }

    public NotificationDelivery(Disclosure disclosure, NotificationChannel channel, String recipient) {
        Assert.notNull(disclosure, "disclosure is required");
        Assert.isTrue(!disclosure.isInitialImport(), "initial imports must not create notifications");
        Assert.notNull(channel, "channel is required");
        this.disclosure = disclosure;
        this.channel = channel;
        if (channel == NotificationChannel.EMAIL) this.recipient=recipient==null?null:EmailAddress.normalize(recipient);
        else {
            Assert.isTrue(recipient!=null && recipient.matches(channel==NotificationChannel.DISCORD
                    ? "discord:[0-9a-f]{64}" : "kakao:[0-9a-f-]{36}"),"Invalid notification target");
            this.recipient=recipient;
        }
        this.nextAttemptAt = createdAt;
    }

    public void beginAttempt() {
        Assert.state(status == NotificationStatus.PENDING || status == NotificationStatus.FAILED,
                "only pending or failed deliveries can be attempted");
        status = NotificationStatus.PROCESSING;
        attemptCount++;
        lastAttemptAt = Instant.now();
        lastError = null;
        nextAttemptAt = null;
        retryable = false;
        claimToken = UUID.randomUUID();
    }

    public void markSent() {
        Assert.state(status == NotificationStatus.PROCESSING, "delivery is not processing");
        status = NotificationStatus.SENT;
        sentAt = Instant.now();
        claimToken = null;
    }

    public void markFailed(String error) {
        Assert.state(status == NotificationStatus.PROCESSING, "delivery is not processing");
        Assert.hasText(error, "error is required");
        status = NotificationStatus.FAILED;
        String normalizedError = error.strip();
        lastError = normalizedError.substring(0, Math.min(normalizedError.length(), 2000));
        retryable = true;
        nextAttemptAt = Instant.now();
        claimToken = null;
    }

    public void markUnknown() {
        Assert.state(status == NotificationStatus.PROCESSING, "delivery is not processing");
        status = NotificationStatus.UNKNOWN;
        lastError = "TRANSPORT_UNCERTAIN";
        retryable = false;
        nextAttemptAt = null;
        claimToken = null;
    }

    public Long getId() { return id; }
    public Disclosure getDisclosure() { return disclosure; }
    public NotificationChannel getChannel() { return channel; }
    public NotificationStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getSentAt() { return sentAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public String getRecipient() { return recipient; }
    public String getExternalMessageId() { return externalMessageId; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public boolean isRetryable() { return retryable; }
}
