package com.dartwatch.kakao;

import com.dartwatch.discord.*;
import com.dartwatch.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KakaoWiringTests {
    @Configuration(proxyBeanMethods = false)
    @Import({EmailNotificationSender.class, DiscordNotificationSender.class, KakaoNotificationSender.class,
            KakaoLocalController.class, KakaoLocalConfiguration.class})
    static class Senders { }
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(KakaoConfiguration.class, DiscordConfiguration.class, Senders.class)
            .withBean(ObjectMapper.class, ObjectMapper::new).withBean(Clock.class, Clock::systemUTC)
            .withBean(KakaoTokenService.class, () -> mock(KakaoTokenService.class))
            .withBean(KakaoOAuthService.class, () -> mock(KakaoOAuthService.class))
            .withBean(NotificationProperties.class, NotificationProperties::new).withBean(MailProperties.class, MailProperties::new);
    @Test void disabledByDefaultAndExistingDispatcherStillUsesEmail() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(KakaoLocalController.class);
            assertThat(context.getBean(NotificationSender.class)).isInstanceOf(EmailNotificationSender.class);
            assertThat(context.getBean(KakaoNotificationSender.class).send(null).error()).isEqualTo("KAKAO_DISABLED");
            verifyNoInteractions(context.getBean(KakaoTokenService.class));
        });
    }
    @Test void localEndpointsOnlyExistWithExplicitLoopbackConfiguration() {
        runner.withPropertyValues("dartwatch.kakao.local-connect-enabled=true", "server.address=127.0.0.1")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KakaoLocalController.class));
        runner.withPropertyValues("dartwatch.kakao.local-connect-enabled=true", "server.address=0.0.0.0")
                .run(context -> assertThat(context).hasFailed());
    }
}
