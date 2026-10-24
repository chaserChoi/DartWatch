package com.dartwatch.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(DiscordProperties.class)
public class DiscordConfiguration {
    @Bean
    DiscordWebhookClient discordWebhookClient(DiscordProperties properties, ObjectMapper mapper, Clock clock) {
        var http = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.getReadTimeout());
        return new DiscordWebhookClient(RestClient.builder().requestFactory(factory).build(), properties, mapper, clock);
    }
}
