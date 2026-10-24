package com.dartwatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.dartwatch.company.CompanyDirectoryStore;
import com.dartwatch.company.CompanyRepository;
import com.dartwatch.disclosure.DisclosureRepository;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import com.dartwatch.notification.NotificationRuleRepository;
import com.dartwatch.notification.NotificationWorkStore;
import com.dartwatch.notification.NotificationDeliveryRepository;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

// The default smoke suite verifies the web application without requiring a local database.
@SpringBootTest(properties = {
        "spring.config.import=",
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration"
})
@AutoConfigureMockMvc
class DartWatchApplicationTests {

    @MockitoBean CompanyRepository companies;
    @MockitoBean WatchedCompanyRepository watchedCompanies;
    @MockitoBean DisclosureRepository disclosures;
    @MockitoBean CompanyDirectoryStore directoryStore;
    @MockitoBean NotificationRuleRepository notificationRules;
    @MockitoBean NotificationWorkStore notificationWorkStore;
    @MockitoBean com.dartwatch.notification.NotificationChannelService notificationChannelService;
    @MockitoBean com.dartwatch.kakao.KakaoTokenStore kakaoTokenStore;
    @MockitoBean NotificationDeliveryRepository notificationDeliveries;

    @Autowired
    private MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerMapping mappings;

    @Test
    void healthReturnsUp() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void openApiDocumentsHealthEndpoint() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/health'].get").exists());
    }

    @Test
    void swaggerUiRedirectsToIndex() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));
    }

    @Test
    void swaggerUiIndexIsServed() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/companies", "/api/companies?page=-1&query=x", "/api/notifications?status=sent",
            "/api/notifications?size=101", "/api/disclosures?companyId=0", "/api/disclosures?companyId=wrong",
            "/api/disclosures?type=PERIODIC", "/api/disclosures?type=a", "/api/disclosures?type=",
            "/api/disclosures?query=", "/api/disclosures?page=-1", "/api/disclosures?size=0",
            "/api/disclosures?size=101", "/api/companies/sync?refresh=invalid"})
    void invalidQueriesUseTheSameProblemDetail(String uri) throws Exception {
        // The last URI only has a POST mapping; exercise binding on that method.
        var request = uri.startsWith("/api/companies/sync") ? post(uri) : get(uri);
        mockMvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    void invalidBodiesCannotRegisterWatchOrSendMail() throws Exception {
        for (String body : new String[] {"{}", "null", "{\"companyId\":0}", "{\"companyId\":-1}", "{"}) {
            mockMvc.perform(post("/api/watchlist").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        for (String body : new String[] {"{}", "null", "{\"confirmSend\":false}"}) {
            mockMvc.perform(post("/api/notifications/dispatch").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(companies);
        verify(notificationWorkStore, never()).claimOne();
        verify(notificationWorkStore, never()).enqueue(any());
    }

    @Test
    void methodMediaAndMissingEndpointErrorsUseProblemDetail() throws Exception {
        mockMvc.perform(get("/api/collections")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/watchlist").contentType(MediaType.TEXT_PLAIN).content("private input"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(get("/api/missing")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
        mockMvc.perform(get("/api/health").accept(MediaType.TEXT_PLAIN)).andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void databaseAndUnexpectedFailuresNeverExposeRawDiagnostics() throws Exception {
        when(companies.search(anyString(), any())).thenThrow(new DataAccessResourceFailureException("password=private URI"))
                .thenThrow(new IllegalStateException("token=private"));
        var unavailable = mockMvc.perform(get("/api/companies").param("query", "x")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("PERSISTENCE_UNAVAILABLE")).andReturn();
        var internal = mockMvc.perform(get("/api/companies").param("query", "x")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        assertThat(unavailable.getResponse().getContentAsString() + internal.getResponse().getContentAsString())
                .doesNotContain("password", "token", "private", "IllegalStateException");
    }

    @Test
    void openApiMatchesEveryActualApiRouteAndDocumentsContracts() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        var document = mapper.readTree(result.getResponse().getContentAsString());
        Set<String> actualRoutes = new HashSet<>();
        mappings.getHandlerMethods().forEach((mapping, handler) -> mapping.getPatternValues().stream()
                .filter(path -> path.startsWith("/api/")).forEach(path -> mapping.getMethodsCondition().getMethods()
                        .forEach(method -> actualRoutes.add(method.name().toLowerCase() + " " + path))));
        Set<String> documentedRoutes = new HashSet<>();
        document.path("paths").properties().forEach(path -> path.getValue().properties().forEach(operation -> {
            documentedRoutes.add(operation.getKey() + " " + path.getKey());
            assertThat(operation.getValue().path("summary").asText()).isNotBlank();
            assertThat(operation.getValue().path("tags").size()).isPositive();
        }));
        assertThat(documentedRoutes).isEqualTo(actualRoutes);
        assertThat(document.at("/paths/~1api~1watchlist/post/responses/201").isMissingNode()).isFalse();
        assertThat(document.at("/paths/~1api~1watchlist/post/responses/200").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1watchlist~1{id}/delete/responses/204").isMissingNode()).isFalse();
        assertThat(document.at("/paths/~1api~1watchlist~1{id}/delete/responses/200").isMissingNode()).isTrue();
        assertThat(document.at("/paths/~1api~1disclosures/get/responses/400/content/application~1problem+json/schema/$ref").asText())
                .isEqualTo("#/components/schemas/ApiProblem");
        assertThat(document.at("/components/schemas/RegisterRequest/required").toString()).contains("companyId");
        assertThat(document.at("/components/schemas/DispatchRequest/required").toString()).contains("confirmSend");
        var parameters = document.at("/paths/~1api~1disclosures/get/parameters");
        assertThat(parameters.findValuesAsText("name")).contains("companyId", "query", "type", "page", "size");
        parameters.forEach(parameter -> {
            if ("size".equals(parameter.path("name").asText())) {
                assertThat(parameter.at("/schema/minimum").asInt()).isEqualTo(1);
                assertThat(parameter.at("/schema/maximum").asInt()).isEqualTo(100);
            }
        });
    }
}
