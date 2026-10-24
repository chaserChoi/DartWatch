package com.dartwatch.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@SpringBootTest
@Tag("smtp-live")
@EnabledIfEnvironmentVariable(
        named = "RUN_LIVE_SMTP_TEST",
        matches = "true"
)
public class LiveSmtpSmokeTest {

    @Autowired
    private JavaMailSender mailSender;

    @Test
    void sendRealEmail() {
        String from = System.getenv("MAIL_FROM");
        String to = System.getenv("NOTIFICATION_EMAIL_TO");

        if (from == null || to == null) {
            throw new IllegalStateException(
                    "MAIL_FROM and NOTIFICATION_EMAIL_TO required"
            );
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[DartWatch] SMTP 연결 테스트");
        message.setText(
                "DartWatch SMTP 연결 및 이메일 발송 테스트입니다."
        );

        assertDoesNotThrow(() -> mailSender.send(message));
    }
}
