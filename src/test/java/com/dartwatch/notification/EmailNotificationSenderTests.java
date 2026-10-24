package com.dartwatch.notification;

import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.SocketTimeoutException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailNotificationSenderTests {
    private final JavaMailSender mail = mock(JavaMailSender.class);
    private final NotificationProperties properties = new NotificationProperties();
    private EmailNotificationSender sender;
    private MimeMessage mime;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void configure() {
        var provider = (ObjectProvider<JavaMailSender>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mail);
        var mailProperties = new MailProperties();
        mailProperties.setHost("smtp.invalid");
        properties.setEnabled(true);
        properties.setMailFrom("sender@example.invalid");
        mime = new MimeMessage(Session.getInstance(new Properties()));
        when(mail.createMimeMessage()).thenReturn(mime);
        sender = new EmailNotificationSender(provider, properties, mailProperties);
    }

    @Test
    void composesUtf8PlainTextWithDisclosureLink() throws Exception {
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.SENT);
        assertThat(mime.getSubject()).isEqualTo("[DartWatch] 테스트 기업 신규 공시 알림");
        assertThat(mime.getContent().toString()).contains("정기공시", "한글 보고서", "2026-10-24", "rcpNo=20261024000001");
        assertThat(mime.getAllRecipients()[0].toString()).isEqualTo("recipient@example.invalid");
        verify(mail).send(mime);
    }

    @Test
    void defaultDisabledSenderNeverInvokesSmtp() {
        properties.setEnabled(false);
        clearInvocations(mail);
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.FAILED);
        verifyNoInteractions(mail);
    }

    @Test
    void authenticationFailureIsPermanentAndSanitized() {
        doThrow(new MailAuthenticationException("secret provider diagnostic")).when(mail).send(mime);
        var result = sender.send(message());
        assertThat(result.error()).isEqualTo("AUTHENTICATION");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void temporaryExplicitSmtpRejectionCanRetry() {
        doThrow(new MailSendException("hidden", new SMTPSendFailedException("MAIL", 450, "secret", null, null, null, null)))
                .when(mail).send(mime);
        var result = sender.send(message());
        assertThat(result.error()).isEqualTo("SMTP_TEMPORARY_REJECTION");
        assertThat(result.retryable()).isTrue();
    }

    @Test
    void permanentExplicitSmtpRejectionDoesNotRetry() {
        doThrow(new MailSendException("hidden", new SMTPSendFailedException(".", 550, "secret", null, null, null, null)))
                .when(mail).send(mime);
        assertThat(sender.send(message()).retryable()).isFalse();
    }

    @Test
    void ambiguousTimeoutIsUnknownEvenWhenNestedInFailedMessageMap() {
        var nested = new jakarta.mail.MessagingException("secret", new SocketTimeoutException("secret"));
        doThrow(new MailSendException(Map.of(mime, nested))).when(mail).send(mime);
        var result = sender.send(message());
        assertThat(result.status()).isEqualTo(NotificationStatus.UNKNOWN);
        assertThat(result.error()).isEqualTo("TRANSPORT_UNCERTAIN");
        assertThat(result.retryable()).isFalse();
    }

    @Test
    void partialAcceptanceAlwaysRemainsUnknown() throws Exception {
        var accepted = new Address[] {new InternetAddress("recipient@example.invalid")};
        var smtp = new SMTPSendFailedException(".", 450, "secret", null, accepted, null, null);
        doThrow(new MailSendException("secret", smtp)).when(mail).send(mime);
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.UNKNOWN);
    }

    static NotificationSender.Message message() {
        return new NotificationSender.Message("recipient@example.invalid", "테스트 기업", "한글 보고서", "A",
                LocalDate.of(2026, 10, 24), "20261024000001");
    }
}
