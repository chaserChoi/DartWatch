package com.dartwatch.kakao;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(KakaoProperties.class)
public class KakaoConfiguration {
    @Bean
    KakaoClient kakaoClient(KakaoProperties properties, ObjectMapper mapper, Clock clock) {
        var http = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http); factory.setReadTimeout(properties.getReadTimeout());
        return new KakaoClient(RestClient.builder().requestFactory(factory).build(), mapper, properties, clock);
    }
}
