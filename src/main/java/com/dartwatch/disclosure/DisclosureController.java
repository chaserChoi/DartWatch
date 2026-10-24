package com.dartwatch.disclosure;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/disclosures")
@Tag(name = "Disclosures", description = "저장된 공시 조회와 공식 유형 분류")
public class DisclosureController {
    private final DisclosureService service;

    public DisclosureController(DisclosureService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "저장 공시 목록 조회", description = "DB만 조회합니다. 필터는 AND로 결합하며 접수일·접수번호 내림차순입니다. "
            + "유형 미지정 시 미분류도 포함하며, 없는 기업/페이지는 빈 목록을 반환합니다.")
    public DisclosureService.Disclosures list(
            @Parameter(description = "기업 DB 식별자. corp_code와 구분", schema = @Schema(minimum = "1")) @RequestParam(required = false) Long companyId,
            @Parameter(description = "기업명 부분 검색 또는 종목코드 일치. 공백 제외 1~200자") @RequestParam(required = false) String query,
            @Parameter(description = "저장된 공식 유형 A~J. F~J는 E로 합치지 않음", schema = @Schema(allowableValues = {"A","B","C","D","E","F","G","H","I","J"})) @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(companyId, query, type, page, size);
    }
}
