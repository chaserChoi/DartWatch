package com.dartwatch.opendart;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.opendart")
public class OpenDartProperties {
    private String apiKey = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(30);
    private int pageSize = 100;
    private int maxPages = 1000;
    private int maxRetries = 2;
    private Duration retryInitialDelay = Duration.ofSeconds(1);
    private Duration retryMaxDelay = Duration.ofSeconds(10);
    private Duration rateLimitCooldown = Duration.ofHours(1);

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) {
        if (value < 0 || value > 5) throw new IllegalArgumentException("OpenDART max retries must be 0..5");
        maxRetries = value;
    }
    public Duration getRetryInitialDelay() { return retryInitialDelay; }
    public void setRetryInitialDelay(Duration value) { retryInitialDelay = boundedDelay(value); }
    public Duration getRetryMaxDelay() { return retryMaxDelay; }
    public void setRetryMaxDelay(Duration value) { retryMaxDelay = boundedDelay(value); }
    public Duration getRateLimitCooldown() { return rateLimitCooldown; }
    public void setRateLimitCooldown(Duration value) {
        if (value == null || value.compareTo(Duration.ofMinutes(1)) < 0 || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("OpenDART cooldown must be 1 minute..1 day");
        rateLimitCooldown = value;
    }
    private static Duration boundedDelay(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("OpenDART retry delay must be 1ms..30s");
        return value;
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = value; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException("OpenDART page size must be 1..100");
        pageSize = value;
    }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) {
        if (value < 1 || value > 1000) throw new IllegalArgumentException("OpenDART max pages must be 1..1000");
        maxPages = value;
    }
}
