package com.dartwatch.collection;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/collections")
@Tag(name = "Collections", description = "공시 수집 실행")
public class CollectionController {
    private final DisclosureCollectionService service;

    public CollectionController(DisclosureCollectionService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "활성 관심 기업 수동 수집", description = "수집만 실행하며 즉시 분류·메일 발송은 하지 않습니다. "
            + "HTTP 200이어도 본문의 COMPLETED/PARTIAL_FAILED/FAILED 및 기업별 결과를 확인하세요. "
            + "자동 알림이 켜져 있으면 저장 데이터가 이후 자동 주기의 대상이 될 수 있습니다.")
    public DisclosureCollectionService.CollectionResult collect() { return service.collectAll(); }
}
