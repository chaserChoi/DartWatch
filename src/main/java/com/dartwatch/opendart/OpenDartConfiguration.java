package com.dartwatch.opendart;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpenDartProperties.class)
public class OpenDartConfiguration {
    @Bean
    OpenDartClient openDartClient(OpenDartProperties properties, CorpCodeParser parser, ObjectMapper mapper) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        var restClient = RestClient.builder().baseUrl("https://opendart.fss.or.kr/api")
                .requestFactory(factory).build();
        return new OpenDartClient(restClient, properties, parser, mapper);
    }

    @Bean
    Clock dartWatchClock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
