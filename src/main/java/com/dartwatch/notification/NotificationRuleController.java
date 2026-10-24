package com.dartwatch.notification;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/watchlist/{watchId}")
public class NotificationRuleController {
    private final NotificationRuleService rules;
    private final NotificationFilterService filters;

    public NotificationRuleController(NotificationRuleService rules, NotificationFilterService filters) {
        this.rules = rules;
        this.filters = filters;
    }

    @GetMapping("/notification-rules")
    public NotificationRuleService.Settings get(@PathVariable Long watchId) { return rules.get(watchId); }

    @PutMapping("/notification-rules/{type}")
    public NotificationRuleService.Settings put(@PathVariable Long watchId, @PathVariable String type,
                                                @RequestBody EnabledRequest request) {
        return rules.putRule(watchId, type, request.enabled());
    }

    @PatchMapping("/notifications")
    public NotificationRuleService.Settings enable(@PathVariable Long watchId, @RequestBody EnabledRequest request) {
        return rules.setNotifications(watchId, request.enabled());
    }

    @GetMapping("/notification-candidates")
    public NotificationFilterService.Candidates preview(@PathVariable Long watchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return filters.preview(watchId, page, size);
    }

    public record EnabledRequest(Boolean enabled) { }
}
