package com.dartwatch.company;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/companies")
@Tag(name = "Companies", description = "기업 고유번호 동기화와 DB 검색")
public class CompanyController {
    private final CompanyService service;
    private final CompanySyncService syncService;

    public CompanyController(CompanyService service, CompanySyncService syncService) {
        this.service = service;
        this.syncService = syncService;
    }

    @GetMapping
    @Operation(summary = "기업 검색", description = "DB만 조회합니다. 기업명 부분 검색 또는 종목코드 일치 검색입니다.")
    public CompanyService.SearchResult search(@Parameter(description = "필수 검색어, 공백 제외 1~200자") @RequestParam String query, @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.search(query, page, size);
    }

    @PostMapping("/sync")
    @Operation(summary = "기업 고유번호 동기화", description = "초기 동기화 후 DB를 재사용합니다. refresh=true일 때 OpenDART 전체 ZIP을 다시 다운로드합니다.")
    public CompanySyncService.SyncResult synchronize(@Parameter(description = "완료된 기업 목록도 다시 다운로드할지 여부") @RequestParam(defaultValue = "false") boolean refresh) {
        return syncService.synchronize(refresh);
    }
}
