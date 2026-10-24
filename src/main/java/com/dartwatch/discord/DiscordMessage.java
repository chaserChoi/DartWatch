package com.dartwatch.discord;

import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.notification.NotificationSender;
import java.util.List;
import java.util.Map;
import org.springframework.util.Assert;

final class DiscordMessage {
    private DiscordMessage() { }

    static Map<String, Object> create(NotificationSender.Message message) {
        Assert.notNull(message, "message required");
        Assert.isTrue(message.rceptNo() != null && message.rceptNo().matches("[0-9]{14}"), "Invalid receipt");
        Assert.notNull(message.receiptDate(), "date required");
        var type = DisclosureType.mvpType(message.typeCode());
        String label = switch (type) {
            case PERIODIC -> "정기공시";
            case MATERIAL_EVENT -> "주요사항보고";
            case ISSUANCE -> "발행공시";
            case SHAREHOLDING -> "지분공시";
            case OTHER -> "기타공시";
            default -> throw new IllegalArgumentException("Unsupported type");
        };
        String company = text(message.companyName(), 256);
        String report = text(message.reportName(), 1024);
        String link = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + message.rceptNo();
        // One embed, < 1600 total text characters; no external input is used as a URL or mention policy.
        return Map.of("allowed_mentions", Map.of("parse", List.of()), "embeds", List.of(Map.of(
                "title", "DartWatch 신규 공시", "url", link,
                "fields", List.of(Map.of("name", "기업명", "value", company),
                        Map.of("name", "보고서명", "value", report),
                        Map.of("name", "공시 유형", "value", message.typeCode() + " · " + label),
                        Map.of("name", "접수일", "value", message.receiptDate().toString()),
                        Map.of("name", "DART 원문", "value", link)))));
    }

    private static String text(String value, int limit) {
        Assert.hasText(value, "text required");
        String clean = value.replace("@", "@\u200B").replaceAll("[\\p{Cntrl}]", " ").strip();
        Assert.hasText(clean, "text required");
        if (clean.length() <= limit) return clean;
        int end = limit - 1;
        if (Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }
}
