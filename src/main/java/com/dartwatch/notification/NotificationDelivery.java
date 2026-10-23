package com.dartwatch.notification;

import com.dartwatch.disclosure.Disclosure;
import jakarta.persistence.*;
import java.time.Instant;
import org.springframework.util.Assert;

@Entity
@Table(name = "notification_delivery", uniqueConstraints = @UniqueConstraint(
        name = "uk_notification_delivery_target", columnNames = {"disclosure_id", "channel"}))
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(nullable = false)
    private int attemptCount;

    private Instant lastAttemptAt;

    @Column(length = 2000)
    private String lastError;

    private Instant sentAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected NotificationDelivery() {
    }

    public NotificationDelivery(Disclosure disclosure, NotificationChannel channel) {
        Assert.notNull(disclosure, "disclosure is required");
        Assert.isTrue(!disclosure.isInitialImport(), "initial imports must not create notifications");
        Assert.notNull(channel, "channel is required");
        this.disclosure = disclosure;
        this.channel = channel;
    }

    public void beginAttempt() {
        Assert.state(status == NotificationStatus.PENDING || status == NotificationStatus.FAILED,
                "only pending or failed deliveries can be attempted");
        status = NotificationStatus.SENDING;
        attemptCount++;
        lastAttemptAt = Instant.now();
        lastError = null;
    }

    public void markSent() {
        Assert.state(status == NotificationStatus.SENDING, "delivery is not sending");
        status = NotificationStatus.SENT;
        sentAt = Instant.now();
    }

    public void markFailed(String error) {
        Assert.state(status == NotificationStatus.SENDING, "delivery is not sending");
        Assert.hasText(error, "error is required");
        status = NotificationStatus.FAILED;
        String normalizedError = error.strip();
        lastError = normalizedError.substring(0, Math.min(normalizedError.length(), 2000));
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
}
