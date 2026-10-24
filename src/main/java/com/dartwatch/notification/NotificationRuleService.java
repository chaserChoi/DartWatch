package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.DisclosureType;
import com.dartwatch.watchlist.WatchedCompany;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class NotificationRuleService {
    private final WatchedCompanyRepository watched;
    private final NotificationRuleRepository rules;
    private final Clock clock;

    public NotificationRuleService(WatchedCompanyRepository watched, NotificationRuleRepository rules, Clock clock) {
        this.watched = watched;
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Settings get(Long watchId) {
        validateId(watchId);
        return settings(watched.findById(watchId).orElseThrow(NotificationRuleService::notFound));
    }

    @Transactional
    public Settings setNotifications(Long watchId, Boolean enabled) {
        validateId(watchId);
        Assert.notNull(enabled, "enabled is required");
        var watch = watched.findForUpdate(watchId).orElseThrow(NotificationRuleService::notFound);
        watch.setNotificationsEnabled(enabled, clock.instant());
        return settings(watch);
    }

    @Transactional
    public Settings putRule(Long watchId, String code, Boolean enabled) {
        validateId(watchId);
        Assert.notNull(enabled, "enabled is required");
        var type = DisclosureType.mvpType(code);
        var watch = watched.findForUpdate(watchId).orElseThrow(NotificationRuleService::notFound);
        var rule = rules.findByWatchedCompanyIdAndDisclosureTypeAndChannel(watchId, type, NotificationChannel.EMAIL)
                .orElseGet(() -> new NotificationRule(watch, type, NotificationChannel.EMAIL, clock.instant()));
        rule.setEnabled(enabled, clock.instant());
        rules.saveAndFlush(rule);
        return settings(watch);
    }

    private Settings settings(WatchedCompany watch) {
        var views = rules.findByWatchedCompanyIdOrderByIdAsc(watch.getId()).stream()
                .map(rule -> new RuleView(rule.getId(), rule.getDisclosureType().getDartCode(), rule.isEnabled(),
                        rule.getEnabledSince(), rule.getDisclosureType().isMvpNotificationType())).toList();
        return new Settings(watch.getId(), watch.isNotificationsEnabled(), watch.getNotificationsEnabledSince(), views);
    }

    private static void validateId(Long id) { Assert.isTrue(id != null && id > 0, "Invalid watch id"); }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "WATCH_NOT_FOUND", "관심 기업을 찾을 수 없습니다.");
    }

    public record RuleView(Long id, String type, boolean enabled, Instant enabledSince, boolean mvpSupported) { }
    public record Settings(Long watchId, boolean notificationsEnabled, Instant enabledSince, List<RuleView> rules) { }
}
