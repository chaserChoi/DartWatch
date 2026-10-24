package com.dartwatch.notification;

import com.dartwatch.discord.*;
import com.dartwatch.kakao.*;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/** Local configuration only: never probes an external service or refreshes a token. */
@Component
public class NotificationTargets {
    private final NotificationProperties email;
    private final DiscordProperties discord;
    private final DiscordWebhookClient webhook;
    private final KakaoProperties kakao;
    private final KakaoTokenService tokens;
    public NotificationTargets(NotificationProperties email, DiscordProperties discord, DiscordWebhookClient webhook,
                               KakaoProperties kakao, KakaoTokenService tokens) {
        this.email=email; this.discord=discord; this.webhook=webhook; this.kakao=kakao; this.tokens=tokens;
    }
    public List<Target> all() { return Arrays.stream(NotificationChannel.values()).map(this::get).toList(); }
    public Target get(NotificationChannel channel) {
        boolean enabled = switch (channel) {
            case EMAIL -> email.isEmailEnabled(); case DISCORD -> discord.isEnabled(); case KAKAO -> kakao.isEnabled();
        };
        if (!enabled) return new Target(channel, null, false, "DISABLED");
        try {
            String recipient;
            if (channel == NotificationChannel.EMAIL) {
                EmailAddress.normalize(email.getMailFrom());
                recipient=EmailAddress.normalize(email.getEmailTo());
            } else if (channel == NotificationChannel.DISCORD) recipient=webhook.targetIdentifier();
            else {
                // The required link must be valid before automatic work can be created.
                KakaoNotificationSender.validateLink(kakao);
                recipient=tokens.targetIdentifier();
            }
            return new Target(channel, recipient, recipient != null,
                    recipient != null ? (channel == NotificationChannel.KAKAO ? "CONNECTED" : "CONFIGURED")
                            : (channel == NotificationChannel.KAKAO ? "REAUTH_REQUIRED" : "CONFIGURATION_REQUIRED"));
        } catch (IllegalArgumentException | KakaoFailure ex) {
            return new Target(channel, null, false, "CONFIGURATION_REQUIRED");
        }
    }
    public record Target(NotificationChannel channel, String recipient, boolean available, String state) { }
}
