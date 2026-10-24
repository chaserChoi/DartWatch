package com.dartwatch.company;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {
    private final CompanyService service;
    private final CompanySyncService syncService;

    public CompanyController(CompanyService service, CompanySyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @GetMapping
    public CompanyService.SearchResult search(@RequestParam String query, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.search(query, page, size);
    }

    @PostMapping("/sync")
    public CompanySyncService.SyncResult synchronize(@RequestParam(defaultValue = "false") boolean refresh) {
        return syncService.synchronize(refresh);
    }
}
