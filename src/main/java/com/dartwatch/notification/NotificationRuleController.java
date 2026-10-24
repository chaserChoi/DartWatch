package com.dartwatch.notification;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlist/{watchId}")
@Tag(name = "Notification rules", description = "기업별 알림 조건과 발송 없는 후보 조회")
public class NotificationRuleController {
    private final NotificationRuleService rules;
    private final NotificationFilterService filters;

    public NotificationRuleController(NotificationRuleService rules, NotificationFilterService filters) {
        this.rules = rules;
        this.filters = filters;
    }

    @GetMapping("/notification-rules")
    @Operation(summary = "기업별 알림 설정·조건 조회")
    public NotificationRuleService.Settings get(@PathVariable Long watchId) { return rules.get(watchId); }

    @PutMapping("/notification-rules/{type}")
    @Operation(summary = "공시 유형 조건 등록·수정", description = "A~E만 지원합니다. 껐다가 다시 켜면 활성 기준을 새로 잡아 비활성 기간의 탐지를 제외합니다.")
    public NotificationRuleService.Settings put(@PathVariable Long watchId,
                                                @Parameter(schema = @Schema(allowableValues = {"A","B","C","D","E"})) @PathVariable String type,
                                                @RequestBody EnabledRequest request) {
        return rules.putRule(watchId, type, request.enabled());
    }

    @PatchMapping("/notifications")
    @Operation(summary = "기업별 알림 활성화·비활성화", description = "기본 비활성입니다. SMTP 전역 허용 설정과 별도로 적용합니다.")
    public NotificationRuleService.Settings enable(@PathVariable Long watchId, @RequestBody EnabledRequest request) {
        return rules.setNotifications(watchId, request.enabled());
    }

    @GetMapping("/notification-candidates")
    @Operation(summary = "발송 없는 알림 후보 미리보기", description = "최초 수집·활성화 이전·비활성 기간 공시를 제외합니다. 작업 생성·SMTP 호출은 없습니다.")
    public NotificationFilterService.Candidates preview(@PathVariable Long watchId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return filters.preview(watchId, page, size);
    }

    public record EnabledRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled) { }
}
