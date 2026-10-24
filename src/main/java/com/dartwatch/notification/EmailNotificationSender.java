package com.dartwatch.notification;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.util.MailConnectException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
@org.springframework.context.annotation.Primary
public class EmailNotificationSender implements NotificationSender {
    private final ObjectProvider<JavaMailSender> mailSender;
    private final NotificationProperties properties;
    private final MailProperties mailProperties;

    public EmailNotificationSender(ObjectProvider<JavaMailSender> mailSender, NotificationProperties properties,
                                   MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.mailProperties = mailProperties;
    }

    @Override
    public SendResult send(Message message) {
        if (!properties.isEnabled() || !properties.isEmailEnabled()) return SendResult.failed("NOTIFICATIONS_DISABLED", false);
        var sender = mailSender.getIfAvailable();
        if (sender == null || mailProperties.getHost() == null || mailProperties.getHost().isBlank()) {
            return SendResult.failed("CONFIGURATION", false);
        }
        jakarta.mail.internet.MimeMessage mime;
        try {
            if (message.rceptNo() == null || !message.rceptNo().matches("[0-9]{14}")) throw new IllegalArgumentException();
            mime = sender.createMimeMessage();
            var helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(EmailAddress.normalize(properties.getMailFrom()));
            helper.setTo(EmailAddress.normalize(message.recipient()));
            helper.setSubject("[DartWatch] " + message.companyName().replaceAll("[\\r\\n]", " ") + " 신규 공시 알림");
            helper.setText("""
                    DartWatch 신규 공시 알림

                    기업명: %s
                    보고서명: %s
                    공시 유형: %s
                    접수일: %s
                    DART 원문: https://dart.fss.or.kr/dsaf001/main.do?rcpNo=%s
                    """.formatted(message.companyName(), message.reportName(), typeLabel(message.typeCode()),
                    message.receiptDate(), message.rceptNo()), false);
        } catch (MessagingException | RuntimeException ex) {
            return SendResult.failed("COMPOSITION_FAILED", false);
        }
        try {
            sender.send(mime);
            return SendResult.sent(); // SMTP acceptance, not proof of inbox delivery.
        } catch (MailAuthenticationException ex) {
            return SendResult.failed("AUTHENTICATION", false);
        } catch (MailParseException | MailPreparationException ex) {
            return SendResult.failed("COMPOSITION_FAILED", false);
        } catch (MailException ex) {
            return classifyFailure(ex);
        } catch (RuntimeException ex) {
            return SendResult.unknown();
        }
    }

    static SendResult classifyFailure(Throwable failure) {
        var queue = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        queue.add(failure);
        boolean authentication = false, connection = false, temporary = false, permanent = false, uncertain = false;
        while (!queue.isEmpty() && seen.size() < 100) {
            var current = queue.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof SendFailedException failed) {
                if (failed.getValidSentAddresses() != null && failed.getValidSentAddresses().length > 0) return SendResult.unknown();
                permanent |= failed.getInvalidAddresses() != null && failed.getInvalidAddresses().length > 0;
            }
            for (var suppressed : current.getSuppressed()) queue.add(suppressed);
            if (current instanceof MailConnectException) {
                // This exception specifically describes failure to establish the transport, including connect timeout.
                connection = true;
                continue;
            }
            int code = current instanceof SMTPSendFailedException smtp ? smtp.getReturnCode()
                    : current instanceof SMTPAddressFailedException address ? address.getReturnCode() : 0;
            temporary |= code >= 400 && code < 500;
            permanent |= code >= 500 && code < 600;
            authentication |= current instanceof AuthenticationFailedException || current instanceof MailAuthenticationException;
            boolean connectFailure = current instanceof ConnectException || current instanceof UnknownHostException;
            connection |= connectFailure;
            uncertain |= current instanceof IOException && !connectFailure;
            if (current.getCause() != null) queue.add(current.getCause());
            if (current instanceof MessagingException messaging && messaging.getNextException() != null) queue.add(messaging.getNextException());
            if (current instanceof MailSendException mail) {
                for (var nested : mail.getMessageExceptions()) queue.add(nested);
            }
        }
        // A mixed error graph or an incomplete inspection cannot prove that the message was never accepted.
        if (uncertain || !queue.isEmpty()) return SendResult.unknown();
        if (authentication) return SendResult.failed("AUTHENTICATION", false);
        if (permanent) return SendResult.failed("SMTP_PERMANENT_REJECTION", false);
        if (temporary) return SendResult.failed("SMTP_TEMPORARY_REJECTION", true);
        if (connection) return SendResult.failed("CONNECTION_FAILED", true);
        // Generic IO errors and timeouts may happen after DATA was accepted. Never blindly retry.
        return SendResult.unknown();
    }

    private static String typeLabel(String code) {
        return switch (code) {
            case "A" -> "A · 정기공시";
            case "B" -> "B · 주요사항보고";
            case "C" -> "C · 발행공시";
            case "D" -> "D · 지분공시";
            case "E" -> "E · 기타공시";
            default -> throw new IllegalArgumentException("Unsupported notification type");
        };
    }
}
