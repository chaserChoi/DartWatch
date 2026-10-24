package com.dartwatch.kakao;

import com.dartwatch.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;

import static com.dartwatch.kakao.KakaoFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KakaoSenderTests {
    private final KakaoProperties p = properties();
    private final KakaoTokenService tokens = mock(KakaoTokenService.class);
    private final KakaoClient client = mock(KakaoClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final KakaoNotificationSender sender = new KakaoNotificationSender(p, tokens, client, mapper);
    @BeforeEach void setup() { when(tokens.access()).thenReturn(new KakaoTokenService.Access("mock-access")); }
    @Test void sendsKoreanTextWithReceiptAndExplicitRegisteredServiceLink() throws Exception {
        assertThat(sender.channel()).isEqualTo("KAKAO");
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.SENT);
        var payload = ArgumentCaptor.forClass(String.class);
        verify(client).send(eq("mock-access"), payload.capture());
        var json = mapper.readTree(payload.getValue());
        assertThat(json.path("object_type").asText()).isEqualTo("text");
        assertThat(json.path("text").asText()).contains("테스트 기업", "한글 보고서", "A·정기공시", "2026-10-24", "20261024000001",
                "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20261024000001");
        assertThat(json.at("/link/web_url").asText()).isEqualTo(p.getMessageWebUrl());
        assertThat(json.path("button_title").asText()).isEqualTo("등록 서비스 열기");
    }
    @ParameterizedTest @ValueSource(strings = {"A", "B", "C", "D", "E"})
    void longExternalTextRespects200UnitLimitWithoutDroppingReceiptOrBreakingSurrogates(String type) {
        var m = new NotificationSender.Message(null, "기업😀".repeat(50), "보고서😀".repeat(100), type, message().receiptDate(), message().rceptNo());
        String text = (String) KakaoMessage.create(m, p).get("text");
        assertThat(text.length()).isLessThanOrEqualTo(200);
        assertThat(text).contains("rcpNo=20261024000001", "유형: " + type);
        for (int i = 0; i < text.length(); i++) {
            if (Character.isHighSurrogate(text.charAt(i))) assertThat(Character.isLowSurrogate(text.charAt(++i))).isTrue();
            else assertThat(Character.isLowSurrogate(text.charAt(i))).isFalse();
        }
    }
    @Test void dartButtonRequiresExplicitAssertionOfRegisteredDartOrigin() {
        p.setRegisteredWebOrigin("https://dart.fss.or.kr");
        var payload = KakaoMessage.create(message(), p);
        assertThat(payload.get("button_title")).isEqualTo("DART 원문");
        assertThat(payload.get("link").toString()).contains("rcpNo=20261024000001");
    }
    @ParameterizedTest @ValueSource(strings = {"", "https://elsewhere.example.invalid/x", "https://service.example.invalid@evil.example/x", "https://service.example.invalid:444/x"})
    void unknownOrMismatchedDomainBlocksBeforeAccessOrRefresh(String link) {
        p.setMessageWebUrl(link);
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed("KAKAO_LINK_CONFIGURATION", false));
        verifyNoInteractions(tokens, client);
    }
    @Test void disabledSenderDoesNotReadTokenOrMakeRequest() {
        p.setEnabled(false);
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed("KAKAO_DISABLED", false));
        verifyNoInteractions(tokens, client);
    }
    @Test void explicitRejectedTokenAllowsExactlyOneRefreshAndOneNewAttempt() {
        doThrow(new KakaoFailure(KakaoFailure.Kind.INVALID_TOKEN)).when(client).send(eq("mock-access"), anyString());
        when(tokens.refreshRejected(new KakaoTokenService.Access("mock-access"))).thenReturn(new KakaoTokenService.Access("mock-new"));
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.SENT);
        verify(client).send(eq("mock-new"), anyString());
        verify(client, times(2)).send(anyString(), anyString());
        verify(tokens, times(1)).refreshRejected(any());
    }
    @Test void secondAuthenticationFailureStopsAndRequiresReconnect() {
        doThrow(new KakaoFailure(KakaoFailure.Kind.INVALID_TOKEN)).when(client).send(anyString(), anyString());
        when(tokens.refreshRejected(any())).thenReturn(new KakaoTokenService.Access("mock-new"));
        assertThat(sender.send(message()).error()).isEqualTo("KAKAO_RECONNECT_REQUIRED");
        verify(tokens).reconnect(); verify(client, times(2)).send(anyString(), anyString());
    }
    @ParameterizedTest @EnumSource(value = KakaoFailure.Kind.class, names = {"SERVER", "TRANSPORT", "INVALID_RESPONSE"})
    void uncertainMessageOutcomeIsUnknownAndNeverRetried(KakaoFailure.Kind kind) {
        doThrow(new KakaoFailure(kind)).when(client).send(anyString(), anyString());
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.unknown());
        verify(client, times(1)).send(anyString(), anyString()); verify(tokens, never()).refreshRejected(any());
    }
    @ParameterizedTest @CsvSource({"PERMISSION,KAKAO_PERMISSION", "CODE_REJECTED,KAKAO_TEMPLATE_REJECTED", "RECONNECT_REQUIRED,KAKAO_RECONNECT_REQUIRED"})
    void permanentFailureDoesNotRetry(KakaoFailure.Kind kind, String error) {
        doThrow(new KakaoFailure(kind)).when(client).send(anyString(), anyString());
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed(error, false));
        verify(client, times(1)).send(anyString(), anyString());
    }
    @Test void rateLimitReturnsNotBeforeForFutureDispatcherWithoutInnerRetry() {
        doThrow(new KakaoFailure(KakaoFailure.Kind.RATE_LIMIT, Duration.ofSeconds(30))).when(client).send(anyString(), anyString());
        var result = sender.send(message());
        assertThat(result.retryable()).isTrue(); assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(result.status()).isEqualTo(NotificationStatus.FAILED);
        verify(client, times(1)).send(anyString(), anyString());
    }
    @Test void tokenFailureBeforeSendIsDefiniteUnsent() {
        when(tokens.access()).thenThrow(new KakaoFailure(KakaoFailure.Kind.TRANSPORT));
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed("KAKAO_TOKEN_UNAVAILABLE", false));
        verifyNoInteractions(client);
    }
}
