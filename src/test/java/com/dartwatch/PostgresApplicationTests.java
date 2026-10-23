package com.dartwatch;

import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void applicationConnectsToPostgres() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT 1")) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void runningServerExposesHealthAndOpenApi() {
        var health = restTemplate.getForEntity("/api/health", String.class);
        assertThat(health.getStatusCode().value()).isEqualTo(200);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        var apiDocs = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode().value()).isEqualTo(200);
        assertThat(apiDocs.getBody()).contains("/api/health");
    }

    @Test
    void runningServerServesSwaggerUi() {
        var response = restTemplate.getForEntity("/swagger-ui.html", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Swagger UI");
    }
}
