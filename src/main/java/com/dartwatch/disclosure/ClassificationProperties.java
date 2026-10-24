package com.dartwatch.disclosure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.classification")
public class ClassificationProperties {
    private int batchSize = 100;
    private int windowDays = 30;
    private Duration retryInterval = Duration.ofHours(24);

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) {
        if (value < 1 || value > 1000) throw new IllegalArgumentException("Classification batch size must be 1..1000");
        batchSize = value;
    }
    public int getWindowDays() { return windowDays; }
    public void setWindowDays(int value) {
        if (value < 1 || value > 90) throw new IllegalArgumentException("Classification window must be 1..90 days");
        windowDays = value;
    }
    public Duration getRetryInterval() { return retryInterval; }
    public void setRetryInterval(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalArgumentException("Classification retry interval must be at least one minute");
        }
        retryInterval = value;
    }
}
