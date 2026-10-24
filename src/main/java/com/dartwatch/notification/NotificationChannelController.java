package com.dartwatch.notification;

import java.util.List;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name="Notification channels",description="기업별 채널 선택·로컬 연결 상태. 조회/설정은 전송하지 않습니다.")
public class NotificationChannelController {
    private final NotificationChannelService service;
    public NotificationChannelController(NotificationChannelService service) { this.service=service; }
    @GetMapping("/api/watchlist/{watchId}/notification-channels")
    @Operation(summary="관심 기업 채널 선택 조회",description="기존 EMAIL 기본 선택을 유지하며 DISCORD/KAKAO는 명시적 활성화가 필요합니다.")
    public List<NotificationChannelService.Selection> get(@PathVariable Long watchId) { return service.get(watchId); }
    @PutMapping("/api/watchlist/{watchId}/notification-channels/{channel}")
    @Operation(summary="채널 활성화·비활성화",description="enabled 필수. 신규/재활성화 시 과거 탐지는 제외합니다. 유형 조건은 기존 A~E 공통 조건을 사용합니다.")
    public List<NotificationChannelService.Selection> put(@PathVariable Long watchId,@PathVariable NotificationChannel channel,@RequestBody Request request) {
        return service.put(watchId,channel,request.enabled());
    }
    @GetMapping("/api/notification-channels")
    @Operation(summary="채널 사용 가능 상태",description="외부 호출 없이 로컬 설정/토큰만 확인합니다. CONFIGURED는 실제 서비스 연결 성공을 보장하지 않습니다. 비밀값을 반환하지 않습니다.")
    public NotificationChannelService.Availability availability() { return service.availability(); }
    public record Request(@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED) Boolean enabled) { }
}
