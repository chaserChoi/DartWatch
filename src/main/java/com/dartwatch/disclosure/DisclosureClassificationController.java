package com.dartwatch.disclosure;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/watchlist/{watchId}/disclosure-classifications")
public class DisclosureClassificationController {
    private final DisclosureClassificationService service;

    public DisclosureClassificationController(DisclosureClassificationService service) { this.service = service; }

    @PostMapping
    public DisclosureClassificationService.Result classify(@PathVariable Long watchId) {
        return service.classify(watchId);
    }
}
