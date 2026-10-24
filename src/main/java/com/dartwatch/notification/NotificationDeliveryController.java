package com.dartwatch.notification;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.Assert;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Notification deliveries", description = "발송 작업 생성·전송 및 페이지 이력")
public class NotificationDeliveryController {
    private final NotificationDispatchService dispatcher;
    private final NotificationHistoryService history;

    public NotificationDeliveryController(NotificationDispatchService dispatcher, NotificationHistoryService history) {
        this.dispatcher = dispatcher;
        this.history = history;
    }

    @PostMapping("/api/watchlist/{watchId}/notification-jobs")
    @Operation(summary = "적격 알림 작업 생성", description = "활성 채널별 PENDING만 생성합니다. 외부 발송은 하지 않으며 전역 알림 활성화가 필요합니다.")
    public Map<String, Integer> enqueue(@PathVariable Long watchId) {
        return Map.of("enqueued", dispatcher.enqueue(watchId));
    }

    @PostMapping("/api/notifications/dispatch")
    @Operation(summary = "채널별 전송·안전한 재시도 실행", description = "선택된 이메일·Discord·Kakao로 실제 전송될 수 있습니다. 전역/채널 활성화와 confirmSend=true가 필요합니다. "
            + "SENT/UNKNOWN은 재전송하지 않습니다. 결과 저장 응답이 유실되면 통계상 unknown이더라도 DB는 SENT일 수 있으므로 이력을 확인하세요.")
    public NotificationDispatchService.DispatchResult dispatch(@RequestBody DispatchRequest request) {
        Assert.isTrue(Boolean.TRUE.equals(request.confirmSend()), "Explicit send confirmation is required");
        return dispatcher.dispatch();
    }

    @GetMapping("/api/notifications")
    @Operation(summary = "발송 이력 조회", description = "SENT는 공급자 수락이고 UNKNOWN은 확인이 필요한 불확실 상태입니다. 최종 전달/열람을 의미하지 않습니다. 채널과 상태 필터는 AND로 결합합니다.")
    public NotificationHistoryService.History list(@Parameter(description = "상태 enum 대문자. 생략 시 전체 이력") @RequestParam(required = false) NotificationStatus status,
            @Parameter(description = "EMAIL, DISCORD, KAKAO. 생략 시 전체 채널") @RequestParam(required = false) NotificationChannel channel,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return history.list(status, channel, page, size);
    }

    public record DispatchRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"true"}, description = "실제 발송 명시 확인") Boolean confirmSend) { }
}
