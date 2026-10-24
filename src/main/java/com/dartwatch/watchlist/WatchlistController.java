package com.dartwatch.watchlist;

import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {
    private final WatchlistService service;

    public WatchlistController(WatchlistService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<WatchlistService.WatchView> register(@RequestBody RegisterRequest request) {
        var created = service.register(request.companyId());
        return ResponseEntity.created(URI.create("/api/watchlist/" + created.id())).body(created);
    }

    @GetMapping
    public List<WatchlistService.WatchView> list() { return service.list(); }

    @PatchMapping("/{id}/monitoring")
    public WatchlistService.WatchView monitoring(@PathVariable Long id, @RequestBody MonitoringRequest request) {
        return service.setMonitoring(id, request.enabled());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(Long companyId) { }
    public record MonitoringRequest(Boolean enabled) { }
}
