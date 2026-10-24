package com.dartwatch.discord;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.discord")
public class DiscordProperties {
    private boolean enabled;
    private String webhookUrl = "";
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration readTimeout = Duration.ofSeconds(10);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String value) { webhookUrl = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { validate(value); connectTimeout = value; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { validate(value); readTimeout = value; }
    private static void validate(Duration value) {
        if (value == null || value.compareTo(Duration.ofMillis(1)) < 0 || value.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Discord timeout must be 1ms..1min");
    }
}
