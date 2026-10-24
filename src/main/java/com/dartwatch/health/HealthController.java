package com.dartwatch.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Health")
public class HealthController {

    @Operation(summary = "API 서버 응답 확인", description = "DB·OpenDART·SMTP 연결 상태를 보장하는 readiness 검사가 아닙니다.")
    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    public record HealthResponse(String status) {
    }
}
