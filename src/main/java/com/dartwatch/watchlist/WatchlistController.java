package com.dartwatch.watchlist;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlist")
@Tag(name = "Watchlist", description = "관심 기업과 모니터링 상태 관리")
public class WatchlistController {
    private final WatchlistService service;

    public WatchlistController(WatchlistService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "관심 기업 등록", description = "기업 DB 식별자를 사용합니다. 등록 자체로 수집·메일을 실행하지 않습니다.")
    @ApiResponse(responseCode = "201", description = "등록 완료", useReturnTypeSchema = true)
    public ResponseEntity<WatchlistService.WatchView> register(@RequestBody RegisterRequest request) {
        var created = service.register(request.companyId());
        return ResponseEntity.created(URI.create("/api/watchlist/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "관심 기업 목록", description = "모니터링 상태와 최근 수집 시도·성공 기준·오류를 조회합니다.")
    public List<WatchlistService.WatchView> list() { return service.list(); }

    @PatchMapping("/{id}/monitoring")
    @Operation(summary = "기업 모니터링 활성화·비활성화")
    public WatchlistService.WatchView monitoring(@PathVariable Long id, @RequestBody MonitoringRequest request) {
        return service.setMonitoring(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "관심 기업 삭제", description = "기업·저장 공시·발송 이력은 보존합니다.")
    @ApiResponse(responseCode = "204", description = "삭제 완료")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", description = "기업 DB 식별자") Long companyId) { }
    public record MonitoringRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean enabled) { }
}
