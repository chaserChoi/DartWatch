package com.dartwatch.disclosure;

import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/watchlist/{watchId}/disclosure-classifications")
@Tag(name = "Disclosures")
public class DisclosureClassificationController {
    private final DisclosureClassificationService service;

    public DisclosureClassificationController(DisclosureClassificationService service) { this.service = service; }

    @PostMapping
    @Operation(summary = "미분류 공시 한 배치 분류", description = "OpenDART A~E 유형별 조회와 rcept_no로 연결합니다. "
            + "확인된 유형은 재사용하고 F~J/확인 불가는 미분류로 유지합니다. 이메일을 발송하지 않습니다.")
    public DisclosureClassificationService.Result classify(@PathVariable Long watchId) {
        return service.classify(watchId);
    }
}
