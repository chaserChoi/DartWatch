package com.dartwatch.notification;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.Assert;

@RestController
public class NotificationDeliveryController {
    private final NotificationDispatchService dispatcher;
    private final NotificationHistoryService history;

    public NotificationDeliveryController(NotificationDispatchService dispatcher, NotificationHistoryService history) {
        this.dispatcher = dispatcher;
        this.history = history;
    }

    @PostMapping("/api/watchlist/{watchId}/notification-jobs")
    public Map<String, Integer> enqueue(@PathVariable Long watchId) {
        return Map.of("enqueued", dispatcher.enqueue(watchId));
    }

    @PostMapping("/api/notifications/dispatch")
    public NotificationDispatchService.DispatchResult dispatch(@RequestBody DispatchRequest request) {
        Assert.isTrue(Boolean.TRUE.equals(request.confirmSend()), "Explicit send confirmation is required");
        return dispatcher.dispatch();
    }

    @GetMapping("/api/notifications")
    public NotificationHistoryService.History list(@RequestParam(required = false) NotificationStatus status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return history.list(status, page, size);
    }

    public record DispatchRequest(Boolean confirmSend) { }
}
