package com.dartwatch.kakao;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dartwatch.kakao")
public class KakaoProperties {
    private boolean enabled;
    private boolean localConnectEnabled;
    private String restApiKey = "", clientSecret = "", redirectUri = "http://localhost:8080/api/integrations/kakao/callback";
    private String encryptionKey = "", registeredWebOrigin = "", messageWebUrl = "";
    private Duration connectTimeout = Duration.ofSeconds(5), readTimeout = Duration.ofSeconds(10);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public boolean isLocalConnectEnabled() { return localConnectEnabled; }
    public void setLocalConnectEnabled(boolean v) { localConnectEnabled = v; }
    public String getRestApiKey() { return restApiKey; }
    public void setRestApiKey(String v) { restApiKey = v; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String v) { clientSecret = v; }
    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String v) { redirectUri = v; }
    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String v) { encryptionKey = v; }
    public String getRegisteredWebOrigin() { return registeredWebOrigin; }
    public void setRegisteredWebOrigin(String v) { registeredWebOrigin = v; }
    public String getMessageWebUrl() { return messageWebUrl; }
    public void setMessageWebUrl(String v) { messageWebUrl = v; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration v) { validate(v); connectTimeout = v; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration v) { validate(v); readTimeout = v; }
    private static void validate(Duration v) {
        if (v == null || v.compareTo(Duration.ofMillis(1)) < 0 || v.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException("Kakao timeout must be 1ms..1min");
    }
}
