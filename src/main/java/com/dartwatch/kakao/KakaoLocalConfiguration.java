package com.dartwatch.kakao;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@ConditionalOnProperty(name = "dartwatch.kakao.local-connect-enabled", havingValue = "true")
public class KakaoLocalConfiguration implements WebMvcConfigurer {
    private final KakaoLocalGuard guard;
    public KakaoLocalConfiguration(KakaoProperties properties, Environment environment) {
        if (!"none".equalsIgnoreCase(environment.getProperty("server.forward-headers-strategy", "none")))
            throw new IllegalStateException("Kakao local connection does not accept proxy forwarding");
        guard = new KakaoLocalGuard(properties, environment.getProperty("server.address", ""));
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(guard).addPathPatterns("/api/integrations/kakao/**"); }
}
