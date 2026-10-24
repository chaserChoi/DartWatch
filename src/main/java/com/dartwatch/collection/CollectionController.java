package com.dartwatch.collection;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/collections")
public class CollectionController {
    private final DisclosureCollectionService service;

    public CollectionController(DisclosureCollectionService service) { this.service = service; }

    @PostMapping
    public DisclosureCollectionService.CollectionResult collect() { return service.collectAll(); }
}
