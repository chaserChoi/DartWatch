package com.dartwatch;

import com.dartwatch.company.*;
import com.dartwatch.disclosure.*;
import com.dartwatch.discord.*;
import com.dartwatch.kakao.*;
import com.dartwatch.notification.*;
import com.dartwatch.support.OwnedPostgresSchema;
import com.dartwatch.watchlist.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc
class PhaseFiveCIntegrationTests {
    private static final OwnedPostgresSchema SCHEMA=new OwnedPostgresSchema();
    private static final Instant START=Instant.parse("2026-10-24T03:00:00Z");
    private static final String DISCORD="discord:"+"a".repeat(64);
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { SCHEMA.configure(registry); }
    @AfterAll static void cleanup(@Autowired DataSource source) throws Exception { SCHEMA.drop(source); }
    @MockitoBean NotificationSender email;
    @MockitoBean DiscordWebhookClient webhook;
    @MockitoBean KakaoClient kakaoClient;
    @MockitoBean Clock clock;
    @Autowired NotificationProperties properties;
    @Autowired DiscordProperties discord;
    @Autowired KakaoProperties kakao;
    @Autowired KakaoTokenService tokens;
    @Autowired KakaoTokenStore tokenStore;
    @Autowired NotificationWorkStore store;
    @Autowired NotificationDispatchService dispatch;
    @Autowired NotificationChannelService channels;
    @Autowired NotificationRuleService rules;
    @Autowired NotificationHistoryService history;
    @Autowired WatchlistService watches;
    @Autowired CompanyRepository companies;
    @Autowired DisclosureRepository disclosures;
    @Autowired NotificationDeliveryRepository deliveries;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource source;
    @Autowired MockMvc mvc;
    @Autowired DiscordNotificationSender discordSender;
    @Autowired KakaoNotificationSender kakaoSender;
    private Company company;
    private Long watch;
    @BeforeEach void setup() throws Exception {
        SCHEMA.verifyOwnership(source);
        for (var table:List.of("notification_delivery","notification_rule","disclosure","watched_company","company","notification_email_settings","notification_channel_settings","kakao_connection"))
            jdbc.update("DELETE FROM "+table);
        when(clock.instant()).thenReturn(START); when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        properties.setEnabled(true); properties.setEmailEnabled(true); properties.setEmailTo("recipient@example.invalid");
        properties.setMailFrom("sender@example.invalid"); properties.setMaxRetries(1); properties.setBatchSize(20);
        properties.setRetryInterval(Duration.ofMinutes(10));
        discord.setEnabled(true); when(webhook.targetIdentifier()).thenReturn(DISCORD);
        kakao.setEnabled(true); kakao.setRestApiKey("mock-client-id");
        kakao.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        kakao.setRegisteredWebOrigin("https://service.example.invalid"); kakao.setMessageWebUrl("https://service.example.invalid/disclosures");
        when(kakaoClient.exchange("mock-code")).thenReturn(new KakaoClient.Tokens("mock-access","mock-refresh",Duration.ofHours(6),Duration.ofDays(60)));
        tokens.connect("mock-code");
        store.synchronizeConfiguration();
        company=companies.saveAndFlush(new Company("00000001","000001","테스트 기업"));
        watch=watches.register(company.getId()).id(); rules.putRule(watch,"A",true); rules.setNotifications(watch,true);
        when(email.send(any())).thenReturn(NotificationSender.SendResult.sent());
        when(webhook.execute(any())).thenReturn(new NotificationSender.SendResult(NotificationStatus.SENT,null,false,"12345",null));
    }
    @AfterEach void disable() { properties.setEnabled(false); properties.setEmailEnabled(true); discord.setEnabled(false); kakao.setEnabled(false); }
    @ParameterizedTest @ValueSource(strings={"EMAIL","DISCORD","KAKAO","ALL","NONE"})
    void selectedChannelsGenerateIndependentJobsAndTerminalResults(String selection) {
        select(selection);
        save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        int expected=selection.equals("ALL")?3:selection.equals("NONE")?0:1;
        assertThat(dispatch.enqueue(watch)).isEqualTo(expected);
        assertThat(dispatch.enqueue(watch)).isZero();
        assertThat(dispatch.dispatch().sent()).isEqualTo(expected);
        assertThat(dispatch.dispatch().sent()).isZero();
        assertThat(deliveries.count()).isEqualTo(expected);
        if (!selection.equals("ALL") && !selection.equals("NONE")) assertThat(deliveries.findAll()).allMatch(n->n.getChannel().name().equals(selection));
        assertThat(dispatch.enqueue(watch)).isZero();
    }
    @Test void smtpAuthenticationFailureDoesNotBlockDiscordAndKakaoOrRetrySentChannels() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        dispatch.enqueue(watch); when(email.send(any())).thenReturn(NotificationSender.SendResult.failed("AUTHENTICATION",false));
        var result=dispatch.dispatch(); assertThat(result.failed()).isEqualTo(1); assertThat(result.sent()).isEqualTo(2);
        at(START.plus(Duration.ofHours(1))); assertThat(restart().dispatch().sent()).isZero();
        verify(email,times(1)).send(any()); verify(webhook,times(1)).execute(any()); verify(kakaoClient,times(1)).send(anyString(),anyString());
    }
    @Test void deletedWebhookIsPermanentAndOtherChannelsRemainSent() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        when(webhook.execute(any())).thenReturn(NotificationSender.SendResult.failed("DISCORD_WEBHOOK_NOT_FOUND",false));
        assertThat(dispatch.dispatch().sent()).isEqualTo(2);
        var row=deliveries.search(NotificationStatus.FAILED,NotificationChannel.DISCORD,org.springframework.data.domain.PageRequest.of(0,20)).getContent().getFirst();
        assertThat(row.isRetryable()).isFalse();
        assertThat(history.list(NotificationStatus.FAILED,NotificationChannel.DISCORD,0,20).items().getFirst().error()).isEqualTo("DISCORD_WEBHOOK_NOT_FOUND");
    }
    @Test void discord429PersistsTargetWideCooldownAcrossRestartAndRetriesOnlyFailedChannel() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        when(webhook.execute(any())).thenReturn(new NotificationSender.SendResult(NotificationStatus.FAILED,"DISCORD_RATE_LIMIT",true,null,Duration.ofMinutes(30)));
        assertThat(dispatch.dispatch().sent()).isEqualTo(2);
        at(START.plus(Duration.ofMinutes(20))); assertThat(restart().dispatch().sent()).isZero();
        verify(webhook,times(1)).execute(any());
        when(webhook.execute(any())).thenReturn(new NotificationSender.SendResult(NotificationStatus.SENT,null,false,"12346",null));
        at(START.plus(Duration.ofMinutes(30))); assertThat(restart().dispatch().sent()).isEqualTo(1);
        verify(email,times(1)).send(any()); verify(kakaoClient,times(1)).send(anyString(),anyString());
        assertThat(history.list(null,NotificationChannel.DISCORD,0,20).items().getFirst().externalMessageId()).isEqualTo("12346");
    }
    @Test void cooldownAlsoProtectsOtherPendingJobsForSameTarget() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); save(2,START.plusSeconds(2),DisclosureType.PERIODIC,false);
        dispatch.enqueue(watch);
        when(webhook.execute(any())).thenReturn(new NotificationSender.SendResult(NotificationStatus.FAILED,"DISCORD_RATE_LIMIT",true,null,Duration.ofDays(1)));
        assertThat(dispatch.dispatch().sent()).isEqualTo(4);
        verify(webhook,times(1)).execute(any());
        assertThat(deliveries.search(NotificationStatus.PENDING,NotificationChannel.DISCORD,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(1);
    }
    @Test void uncertainKakaoOutcomeIsNotRetriedButEmailAndDiscordAreSent() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        doThrow(new KakaoFailure(KakaoFailure.Kind.TRANSPORT)).when(kakaoClient).send(anyString(),anyString());
        assertThat(dispatch.dispatch().unknown()).isEqualTo(1);
        at(START.plus(Duration.ofHours(1))); assertThat(restart().dispatch().unknown()).isZero();
        verify(kakaoClient,times(1)).send(anyString(),anyString());
        assertThat(deliveries.search(NotificationStatus.SENT,null,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(2);
    }
    @Test void expiredAccessRefreshesWithoutChangingKakaoTargetOrResendingOtherChannels() {
        select("ALL"); String target=tokens.targetIdentifier();
        when(kakaoClient.refresh("mock-refresh")).thenReturn(new KakaoClient.Tokens("mock-new",null,Duration.ofHours(6),null));
        at(START.plus(Duration.ofHours(7))); save(1,START.plus(Duration.ofHours(7)),DisclosureType.PERIODIC,false);
        dispatch.enqueue(watch); assertThat(dispatch.dispatch().sent()).isEqualTo(3);
        assertThat(tokens.targetIdentifier()).isEqualTo(target);
        verify(kakaoClient,times(1)).refresh("mock-refresh"); verify(kakaoClient).send(eq("mock-new"),anyString());
    }
    @Test void revokedRefreshFailsOnlyKakaoAndRequiresReauthorization() {
        select("ALL"); at(START.plus(Duration.ofHours(7)));
        when(kakaoClient.refresh(any())).thenThrow(new KakaoFailure(KakaoFailure.Kind.RECONNECT_REQUIRED));
        save(1,START.plus(Duration.ofHours(7)),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        var result=dispatch.dispatch(); assertThat(result.sent()).isEqualTo(2); assertThat(result.failed()).isEqualTo(1);
        assertThat(tokenStore.load().status()).isEqualTo("RECONNECT_REQUIRED");
        verify(kakaoClient,never()).send(anyString(),anyString());
    }
    @Test void perWatchReactivationExcludesDisabledAndDelayedClassificationData() {
        select("DISCORD");
        at(START.plusSeconds(1)); channels.put(watch,NotificationChannel.DISCORD,false);
        save(1,START.plusSeconds(2),null,false);
        at(START.plusSeconds(3)); channels.put(watch,NotificationChannel.DISCORD,true);
        jdbc.update("UPDATE disclosure SET disclosure_type='PERIODIC'");
        assertThat(dispatch.enqueue(watch)).isZero();
        save(2,START.plusSeconds(4),DisclosureType.PERIODIC,false); assertThat(dispatch.enqueue(watch)).isEqualTo(1);
    }
    @Test void enablingNewChannelCannotBackfillOldDetectedRowsEvenWhenClassifiedLater() {
        select("EMAIL"); save(1,START.plusSeconds(1),null,false);
        at(START.plusSeconds(2)); channels.put(watch,NotificationChannel.DISCORD,true);
        jdbc.update("UPDATE disclosure SET disclosure_type='PERIODIC'");
        assertThat(dispatch.enqueue(watch)).isEqualTo(1);
        assertThat(deliveries.findAll().getFirst().getChannel()).isEqualTo(NotificationChannel.EMAIL);
    }
    @Test void channelEnvironmentDisableAndReenableMovesBaselineAndDoesNotCancelOtherChannels() {
        select("ALL"); discord.setEnabled(false); at(START.plusSeconds(1)); store.synchronizeConfiguration();
        save(1,START.plusSeconds(2),DisclosureType.PERIODIC,false);
        discord.setEnabled(true); at(START.plusSeconds(3)); store.synchronizeConfiguration();
        assertThat(dispatch.enqueue(watch)).isEqualTo(2);
        assertThat(deliveries.findAll()).noneMatch(n->n.getChannel()==NotificationChannel.DISCORD);
    }
    @Test void changingWebhookOrReconnectingKakaoDoesNotDeliverOldJobsToNewTarget() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        String previous=tokens.targetIdentifier(); at(START.plusSeconds(2)); tokens.connect("mock-code");
        assertThat(tokens.targetIdentifier()).isNotEqualTo(previous);
        when(webhook.targetIdentifier()).thenReturn("discord:"+"b".repeat(64));
        assertThat(dispatch.dispatch().sent()).isEqualTo(1);
        assertThat(dispatch.enqueue(watch)).isZero();
        assertThat(deliveries.search(NotificationStatus.CANCELLED,null,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(2);
        verify(webhook,never()).execute(any()); verify(kakaoClient,never()).send(anyString(),anyString());
    }
    @Test void differentCompaniesCanSelectDifferentChannelsAndNeedCommonTypeCondition() {
        select("DISCORD"); var second=companies.saveAndFlush(new Company("00000002","000002","다른 기업"));
        Long other=watches.register(second.getId()).id(); channels.put(other,NotificationChannel.EMAIL,false); channels.put(other,NotificationChannel.KAKAO,true);
        rules.setNotifications(other,true);
        var d=disclosures.saveAndFlush(new Disclosure(second,"20261024000003","보고서",LocalDate.of(2026,10,24),DisclosureType.PERIODIC,false));
        jdbc.update("UPDATE disclosure SET collected_at=? WHERE id=?",Timestamp.from(START.plusSeconds(1)),d.getId());
        assertThat(dispatch.enqueue(other)).isZero(); rules.putRule(other,"A",true);
        assertThat(dispatch.enqueue(other)).isEqualTo(1); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        assertThat(dispatch.enqueue(watch)).isEqualTo(1);
        assertThat(dispatch.dispatch().sent()).isEqualTo(2); verify(email,never()).send(any());
    }
    @Test void pastInitialUnmatchedAndDisabledPoliciesNeverGenerateWork() {
        select("ALL"); save(1,START.minusSeconds(1),DisclosureType.PERIODIC,false);
        save(2,START.plusSeconds(1),DisclosureType.PERIODIC,true); save(3,START.plusSeconds(1),DisclosureType.OTHER,false);
        assertThat(dispatch.enqueue(watch)).isZero();
        save(4,START.plusSeconds(1),DisclosureType.PERIODIC,false); watches.setMonitoring(watch,false);
        assertThat(dispatch.enqueue(watch)).isZero();
        watches.setMonitoring(watch,true); rules.setNotifications(watch,false); assertThat(dispatch.enqueue(watch)).isZero();
    }
    @Test void partialCompletionAndStaleProcessingRecoverWithoutResendingSent() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        var first=store.claimOne(); assertThat(store.finish(first,NotificationSender.SendResult.sent())).isTrue();
        var interrupted=store.claimOne(); assertThat(interrupted).isNotNull();
        at(START.plus(Duration.ofMinutes(11)));
        var result=restart().dispatch(); assertThat(result.sent()).isEqualTo(1); assertThat(result.recoveredUnknown()).isEqualTo(1);
        assertThat(restart().dispatch().sent()).isZero();
        assertThat(deliveries.search(NotificationStatus.SENT,null,org.springframework.data.domain.PageRequest.of(0,20)).getTotalElements()).isEqualTo(2);
    }
    @Test void concurrentClaimsAndUniqueConstraintPreventDuplicateWorkAcrossChannels() throws Exception {
        select("ALL"); var disclosure=save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            Callable<Integer> task=()->{gate.await(); return dispatch.enqueue(watch);};
            var one=pool.submit(task); var two=pool.submit(task); gate.countDown();
            assertThat(one.get(5,TimeUnit.SECONDS)+two.get(5,TimeUnit.SECONDS)).isEqualTo(3);
        }
        assertThatThrownBy(()->deliveries.saveAndFlush(new NotificationDelivery(disclosure,NotificationChannel.DISCORD,DISCORD)))
                .isInstanceOf(DataIntegrityViolationException.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(store::claimOne); var two=pool.submit(store::claimOne);
            var a=one.get(5,TimeUnit.SECONDS); var b=two.get(5,TimeUnit.SECONDS);
            assertThat(a.id()).isNotEqualTo(b.id());
        }
        assertThat(deliveries.count()).isEqualTo(3);
    }
    @Test void emailChannelCanBeDisabledWithoutEmailAddressesAndDiscordStillSends() {
        select("DISCORD"); properties.setEmailEnabled(false); properties.setEmailTo(""); properties.setMailFrom("");
        save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        assertThat(dispatch.enqueue(watch)).isEqualTo(1); assertThat(dispatch.dispatch().sent()).isEqualTo(1);
        verify(email,never()).send(any());
    }
    @Test void restApisValidateInputsExposeFilteredHistoryAndNoSecrets() throws Exception {
        mvc.perform(get("/api/watchlist/{id}/notification-channels",watch)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].channel").value("EMAIL")).andExpect(jsonPath("$[0].enabled").value(true));
        mvc.perform(put("/api/watchlist/{id}/notification-channels/DISCORD",watch).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/watchlist/{id}/notification-channels/BAD",watch).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/watchlist/999999/notification-channels")).andExpect(status().isNotFound());
        mvc.perform(put("/api/watchlist/{id}/notification-channels/DISCORD",watch).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        var response=mvc.perform(get("/api/notification-channels")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("mock-access","mock-refresh","mock-client-id","webhook","ciphertext");
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch); dispatch.dispatch();
        mvc.perform(get("/api/notifications").param("channel","DISCORD").param("status","SENT").param("size","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].externalMessageId").value("12345")).andExpect(jsonPath("$.items[0].recipient").value(DISCORD));
        mvc.perform(get("/api/notifications").param("channel","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/notifications").param("size","101")).andExpect(status().isBadRequest());
        var docs=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/notification-channels","/api/watchlist/{watchId}/notification-channels/{channel}","externalMessageId");
    }
    @Test void v6PreservesExistingEmailHistoryAndActivationWithoutEnablingOtherChannels() {
        String legacy="dw_test_"+UUID.randomUUID().toString().replace("-","");
        try {
            Flyway.configure().dataSource(source).schemas(legacy).defaultSchema(legacy).target("5").load().migrate();
            jdbc.update("INSERT INTO "+legacy+".company (id,corp_code,name,created_at) VALUES (1,'00000001','Legacy',CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO "+legacy+".watched_company (id,company_id,monitoring_enabled,created_at,notifications_enabled,notifications_enabled_since) VALUES (1,1,TRUE,CURRENT_TIMESTAMP,TRUE,?)",Timestamp.from(START));
            jdbc.update("INSERT INTO "+legacy+".disclosure (id,company_id,rcept_no,report_name,receipt_date,initial_import,collected_at) VALUES (1,1,'20261024000001','Legacy',CURRENT_DATE,FALSE,CURRENT_TIMESTAMP)");
            jdbc.update("INSERT INTO "+legacy+".notification_delivery (id,disclosure_id,channel,status,attempt_count,last_attempt_at,sent_at,created_at,version,recipient) VALUES (1,1,'EMAIL','SENT',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0,'recipient@example.invalid')");
            jdbc.update("INSERT INTO "+legacy+".notification_email_settings VALUES ('recipient@example.invalid',TRUE,?)",Timestamp.from(START));
            Flyway.configure().dataSource(source).schemas(legacy).defaultSchema(legacy).load().migrate();
            assertThat(jdbc.queryForObject("SELECT status FROM "+legacy+".notification_delivery",String.class)).isEqualTo("SENT");
            assertThat(jdbc.queryForObject("SELECT enabled_since FROM "+legacy+".notification_email_settings",Timestamp.class).toInstant()).isEqualTo(START);
            assertThat(jdbc.queryForList("SELECT channel FROM "+legacy+".watched_notification_channel",String.class)).containsExactly("EMAIL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+legacy+".notification_channel_settings",Integer.class)).isZero();
        } finally {
            if (!legacy.matches("dw_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid owned schema");
            jdbc.execute("DROP SCHEMA IF EXISTS \""+legacy+"\" CASCADE");
        }
    }
    @Test void isolatedOutcomeTransactionFailureQuarantinesOnlyThatChannelAndContinues() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        var wrapped=mock(NotificationWorkStore.class,org.mockito.AdditionalAnswers.delegatesTo(store));
        doAnswer(invocation->{
            NotificationWorkStore.Claim claim=invocation.getArgument(0);
            NotificationSender.SendResult result=invocation.getArgument(1);
            if (claim.channel()==NotificationChannel.DISCORD && result.status()==NotificationStatus.SENT)
                throw new org.springframework.dao.DataAccessResourceFailureException("mock isolated result failure");
            return store.finish(claim,result);
        }).when(wrapped).finish(any(),any());
        var service=new NotificationDispatchService(properties,wrapped,List.of(email,discordSender,kakaoSender),mock(WatchedCompanyRepository.class));
        var result=service.dispatch(); assertThat(result.sent()).isEqualTo(2); assertThat(result.unknown()).isEqualTo(1);
        assertThat(restart().dispatch().sent()).isZero(); verify(webhook,times(1)).execute(any());
    }
    @Test void globalDisableCancelsAllPendingChannelsAndReactivationExcludesOldData() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        properties.setEnabled(false); store.synchronizeConfiguration();
        assertThat(deliveries.findAll()).allMatch(n->n.getStatus()==NotificationStatus.CANCELLED);
        properties.setEnabled(true); at(START.plusSeconds(3)); store.synchronizeConfiguration();
        assertThat(dispatch.enqueue(watch)).isZero(); assertThat(dispatch.dispatch().sent()).isZero();
    }
    @Test void retryLimitStopsOnlyFailingChannelAndNeverRepeatsSuccesses() {
        select("ALL"); save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false); dispatch.enqueue(watch);
        when(webhook.execute(any())).thenReturn(NotificationSender.SendResult.failed("CONNECTION_FAILED",true));
        assertThat(dispatch.dispatch().sent()).isEqualTo(2);
        at(START.plus(Duration.ofMinutes(10))); assertThat(restart().dispatch().failed()).isEqualTo(1);
        at(START.plus(Duration.ofMinutes(20))); assertThat(restart().dispatch().failed()).isZero();
        verify(webhook,times(2)).execute(any()); verify(email,times(1)).send(any());
    }
    @Test void repeatedEmailSelectionPreservesImplicitLegacyActivation() {
        save(1,START.plusSeconds(1),DisclosureType.PERIODIC,false);
        at(START.plusSeconds(2)); channels.put(watch,NotificationChannel.EMAIL,true);
        assertThat(dispatch.enqueue(watch)).isEqualTo(1);
    }
    private void select(String selection) {
        for (var channel:NotificationChannel.values()) channels.put(watch,channel,selection.equals("ALL") || selection.equals(channel.name()));
    }
    private void at(Instant instant) { when(clock.instant()).thenReturn(instant); }
    private NotificationDispatchService restart() {
        return new NotificationDispatchService(properties,store,List.of(email,discordSender,kakaoSender),mock(WatchedCompanyRepository.class));
    }
    private Disclosure save(int receipt,Instant detected,DisclosureType type,boolean initial) {
        var d=disclosures.saveAndFlush(new Disclosure(company,"20261024"+String.format("%06d",receipt),"한글 공시",LocalDate.of(2026,10,24),type,initial));
        jdbc.update("UPDATE disclosure SET collected_at=? WHERE id=?",Timestamp.from(detected),d.getId()); return d;
    }
}
