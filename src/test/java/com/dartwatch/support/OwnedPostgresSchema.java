package com.dartwatch.support;

import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.test.context.DynamicPropertyRegistry;

public final class OwnedPostgresSchema {
    private final String name = "dw_test_" + UUID.randomUUID().toString().replace("-", "");

    public void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.default-schema", () -> name);
        registry.add("spring.flyway.schemas", () -> name);
        registry.add("spring.datasource.hikari.schema", () -> name);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> name);
        registry.add("dartwatch.collection.enabled", () -> "false");
    }

    public void verifyOwnership(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT current_schema()")) {
            if (!result.next() || !name.equals(result.getString(1))) throw new IllegalStateException("Not the owned test schema");
        }
    }

    public void drop(DataSource dataSource) throws Exception {
        if (!name.matches("dw_test_[0-9a-f]{32}")) throw new IllegalStateException("Invalid owned schema");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + name + "\" CASCADE");
        }
    }
}
