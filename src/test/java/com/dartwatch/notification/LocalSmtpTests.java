package com.dartwatch.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.*;

class LocalSmtpTests {
    @Test
    void realJavaMailClientPreservesKoreanEncodingAgainstDiscardOnlyLoopbackServer() throws Exception {
        runLocalServer(false);
    }

    @Test
    void connectionLostAfterDataIsUnknownRatherThanAnAutomaticRetry() throws Exception {
        runLocalServer(true);
    }

    private void runLocalServer(boolean loseFinalReply) throws Exception {
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            var received = new CompletableFuture<String>();
            var worker = new Thread(() -> acceptOne(server, received, loseFinalReply), "discard-only-test-smtp");
            worker.setDaemon(true);
            worker.start();
            var mail = new JavaMailSenderImpl();
            mail.setHost("127.0.0.1");
            mail.setPort(server.getLocalPort());
            mail.getJavaMailProperties().put("mail.smtp.connectiontimeout", "3000");
            mail.getJavaMailProperties().put("mail.smtp.timeout", "3000");
            mail.getJavaMailProperties().put("mail.smtp.writetimeout", "3000");
            var beans = new StaticListableBeanFactory();
            beans.addBean("mail", mail);
            var properties = new NotificationProperties();
            properties.setEnabled(true);
            properties.setMailFrom("sender@example.invalid");
            var mailProperties = new MailProperties();
            mailProperties.setHost("127.0.0.1");
            var sender = new EmailNotificationSender(beans.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class),
                    properties, mailProperties);
            var result = sender.send(EmailNotificationSenderTests.message());
            assertThat(result.status()).isEqualTo(loseFinalReply ? NotificationStatus.UNKNOWN : NotificationStatus.SENT);
            var wire = received.get(5, TimeUnit.SECONDS);
            var decoded = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
            assertThat(decoded.getSubject()).contains("테스트 기업");
            assertThat(decoded.getContent().toString()).contains("한글 보고서", "rcpNo=20261024000001");
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
        }
    }

    private void acceptOne(ServerSocket server, CompletableFuture<String> received, boolean loseReply) {
        try (var socket = server.accept()) {
            socket.setSoTimeout(5000);
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            reply(output, "220 localhost test SMTP");
            String line;
            while ((line = input.readLine()) != null) {
                if (line.startsWith("EHLO") || line.startsWith("HELO")) reply(output, "250 localhost");
                else if (line.startsWith("MAIL FROM") || line.startsWith("RCPT TO") || line.equals("RSET")) reply(output, "250 OK");
                else if (line.equals("DATA")) {
                    reply(output, "354 End with dot");
                    var wire = new StringBuilder();
                    while ((line = input.readLine()) != null && !line.equals(".")) {
                        wire.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    }
                    received.complete(wire.toString());
                    if (loseReply) return;
                    reply(output, "250 Accepted by discard-only test server");
                } else if (line.equals("QUIT")) { reply(output, "221 Bye"); return; }
                else reply(output, "250 OK");
            }
        } catch (Exception ex) { received.completeExceptionally(ex); }
    }

    private void reply(BufferedWriter output, String text) throws IOException {
        output.write(text + "\r\n");
        output.flush();
    }
}
