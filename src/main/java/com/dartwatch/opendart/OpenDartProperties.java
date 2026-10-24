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
