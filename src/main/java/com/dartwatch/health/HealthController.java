package com.dartwatch.health;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @Operation(summary = "Check that the API server is running")
    @GetMapping("/api/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }

    public record HealthResponse(String status) {
    }
}
