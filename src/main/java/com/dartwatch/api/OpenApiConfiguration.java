package com.dartwatch.api;

import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.math.BigDecimal;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenApiCustomizer dartWatchApiDocumentation() {
        return api -> {
            api.setInfo(new Info().title("DartWatch API").version("MVP")
                    .description("단일 사용자 공시 모니터링 API. 수집 응답은 HTTP 상태와 함께 기업별 결과를 확인하세요. "
                            + "SENT는 SMTP 수락이며 UNKNOWN은 자동 재발송하지 않습니다."));
            if (api.getComponents() == null) api.setComponents(new Components());
            var problem = new ObjectSchema();
            problem.addProperty("type", new StringSchema().format("uri"));
            problem.addProperty("title", new StringSchema());
            problem.addProperty("status", new IntegerSchema());
            problem.addProperty("detail", new StringSchema());
            problem.addProperty("instance", new StringSchema().format("uri"));
            problem.addProperty("code", new StringSchema().description("애플리케이션 오류 코드"));
            problem.addProperty("upstreamStatus", new StringSchema().description("제공되는 경우 OpenDART 상태 코드"));
            api.getComponents().addSchemas("ApiProblem", problem);
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                if (operation.getParameters() != null) operation.getParameters().forEach(parameter -> {
                    if (Set.of("page", "size").contains(parameter.getName())) {
                        boolean page = "page".equals(parameter.getName());
                        parameter.setDescription(page ? "0부터 시작하는 페이지 번호" : "페이지 크기, 1~100");
                        var pagination = new IntegerSchema();
                        pagination.setMinimum(BigDecimal.valueOf(page ? 0 : 1));
                        if (!page) pagination.setMaximum(BigDecimal.valueOf(100));
                        pagination.setDefault(page ? 0 : 20);
                        parameter.setSchema(pagination);
                    }
                });
                operation.getResponses().addApiResponse("406", error("지원하지 않는 응답 형식"));
                operation.getResponses().addApiResponse("500", error("처리하지 못한 내부 오류, 민감정보 제외"));
                if ("/api/health".equals(path)) return;
                if (operation.getRequestBody() != null)
                    operation.getResponses().addApiResponse("415", error("지원하지 않는 요청 미디어 타입"));
                if (operation.getParameters() != null || operation.getRequestBody() != null)
                    operation.getResponses().addApiResponse("400", error("잘못된 식별자·파라미터·필수 값·JSON"));
                if (path.contains("{") || ("/api/watchlist".equals(path) && method.name().equals("POST")))
                    operation.getResponses().addApiResponse("404", error("기업 또는 관심 기업을 찾을 수 없음"));
                if (!method.name().equals("GET"))
                    operation.getResponses().addApiResponse("409", error("중복 등록·작업 진행 중·현재 상태와 충돌"));
                operation.getResponses().addApiResponse("503", error("DB 사용 불가 또는 기능/설정/상위 서비스 사용 불가"));
                if (path.equals("/api/companies/sync") || path.endsWith("/disclosure-classifications")) {
                    operation.getResponses().addApiResponse("502", error("OpenDART 인증·요청·HTTP·네트워크·응답 오류"));
                    operation.getResponses().addApiResponse("504", error("OpenDART 응답 시간 초과"));
                }
            }));
        };
    }

    private static ApiResponse error(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    }
}
