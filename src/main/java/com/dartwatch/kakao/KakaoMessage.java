package com.dartwatch.kakao;

import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.notification.NotificationSender;
import java.net.URI;
import java.util.Map;
import org.springframework.util.Assert;

final class KakaoMessage {
    private KakaoMessage() { }
    static Map<String, Object> create(NotificationSender.Message m, KakaoProperties properties) {
        Assert.notNull(m, "message required");
        Assert.isTrue(m.rceptNo() != null && m.rceptNo().matches("[0-9]{14}"), "Invalid receipt");
        Assert.notNull(m.receiptDate(), "date required");
        var type = DisclosureType.mvpType(m.typeCode());
        String label = switch (type) {
            case PERIODIC -> "정기공시"; case MATERIAL_EVENT -> "주요사항보고"; case ISSUANCE -> "발행공시";
            case SHAREHOLDING -> "지분공시"; case OTHER -> "기타공시"; default -> throw new IllegalArgumentException();
        };
        String dart = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + m.rceptNo();
        String link = link(properties, dart);
        String prefix = "기업: " + shortText(m.companyName(), 16) + "\n보고서: ";
        String suffix = "\n유형: " + m.typeCode() + "·" + label + "\n접수: " + m.receiptDate()
                + "\n접수번호: " + m.rceptNo() + "\n원문: " + dart;
        int budget = 200 - prefix.length() - suffix.length();
        Assert.isTrue(budget > 0, "Message exceeds limit");
        return Map.of("object_type", "text", "text", prefix + shortText(m.reportName(), budget) + suffix,
                "link", Map.of("web_url", link, "mobile_web_url", link), "button_title", link.equals(dart) ? "DART 원문" : "등록 서비스 열기");
    }
    private static String link(KakaoProperties p, String dart) {
        try {
            URI origin = URI.create(p.getRegisteredWebOrigin());
            if (origin.getHost() == null || !("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()))
                    || origin.getRawUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null
                    || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))) throw new IllegalArgumentException();
            if ("https://dart.fss.or.kr".equals(origin.toString())) return dart;
            URI url = URI.create(p.getMessageWebUrl());
            if (!origin.getScheme().equals(url.getScheme()) || !origin.getHost().equalsIgnoreCase(url.getHost())
                    || origin.getPort() != url.getPort() || url.getRawUserInfo() != null || url.getRawFragment() != null)
                throw new IllegalArgumentException();
            return url.toString();
        } catch (RuntimeException ex) { throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION); }
    }
    private static String shortText(String value, int length) {
        Assert.hasText(value, "text required");
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        Assert.hasText(clean, "text required");
        if (clean.length() <= length) return clean;
        int end = length - 1;
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end) + "…";
    }
}
