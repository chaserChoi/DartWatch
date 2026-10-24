package com.dartwatch.notification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.notification")
public class NotificationProperties {
    private boolean enabled;
    private String emailTo = "";
    private String mailFrom = "";
    private int maxRetries = 3;
    private int batchSize = 20;
    private Duration retryInterval = Duration.ofMinutes(10);
    private Duration processingTimeout = Duration.ofMinutes(10);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getEmailTo() { return emailTo; }
    public void setEmailTo(String value) { emailTo = value; }
    public String getMailFrom() { return mailFrom; }
    public void setMailFrom(String value) { mailFrom = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) {
        if (value < 0 || value > 10) throw new IllegalArgumentException("Max retries must be 0..10");
        maxRetries = value;
    }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("Batch size must be 1..100");
        batchSize = value;
    }
    public Duration getRetryInterval() { return retryInterval; }
    public void setRetryInterval(Duration value) { checkInterval(value); retryInterval = value; }
    public Duration getProcessingTimeout() { return processingTimeout; }
    public void setProcessingTimeout(Duration value) { checkInterval(value); processingTimeout = value; }
    private static void checkInterval(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0) throw new IllegalArgumentException("Interval must be at least one minute");
    }
}
