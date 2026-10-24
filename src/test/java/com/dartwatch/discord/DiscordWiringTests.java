package com.dartwatch.discord;

import com.dartwatch.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;

class DiscordWiringTests {
    @Configuration(proxyBeanMethods = false)
    @Import({EmailNotificationSender.class, DiscordNotificationSender.class})
    static class Senders { }

    @Test
    void discordDoesNotReplaceTheExistingEmailSenderOrTriggerAnyRequests() {
        var runner = new ApplicationContextRunner().withUserConfiguration(DiscordConfiguration.class, Senders.class)
                .withBean(ObjectMapper.class, ObjectMapper::new).withBean(Clock.class, Clock::systemUTC)
                .withBean(NotificationProperties.class, NotificationProperties::new).withBean(MailProperties.class, MailProperties::new);
        runner.run(context -> {
            assertThat(context.getBean(NotificationSender.class)).isInstanceOf(EmailNotificationSender.class);
            assertThat(context.getBean(NotificationSender.class).channel()).isEqualTo("EMAIL");
            assertThat(context.getBean(DiscordProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(DiscordNotificationSender.class).send(null).error()).isEqualTo("DISCORD_DISABLED");
        });
        runner.withPropertyValues("dartwatch.discord.enabled=true").run(context ->
                assertThat(context.getBean(NotificationSender.class)).isInstanceOf(EmailNotificationSender.class));
    }
}
