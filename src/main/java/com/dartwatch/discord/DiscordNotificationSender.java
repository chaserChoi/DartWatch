package com.dartwatch.discord;

import com.dartwatch.notification.NotificationSender;
import org.springframework.stereotype.Component;

@Component
public class DiscordNotificationSender implements NotificationSender {
    private final DiscordProperties properties;
    private final DiscordWebhookClient client;

    public DiscordNotificationSender(DiscordProperties properties, DiscordWebhookClient client) {
        this.properties = properties;
        this.client = client;
    }
    @Override public String channel() { return "DISCORD"; }

    @Override
    public SendResult send(Message message) {
        if (!properties.isEnabled()) return SendResult.failed("DISCORD_DISABLED", false);
        try {
            return client.execute(DiscordMessage.create(message));
        } catch (IllegalArgumentException ex) {
            return SendResult.failed("COMPOSITION_FAILED", false);
        }
    }
}
