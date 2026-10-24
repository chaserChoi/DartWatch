package com.dartwatch.company;

import com.dartwatch.opendart.CorpRecord;
import java.sql.Types;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CompanyDirectoryStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CompanyDirectoryStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean isSynchronized() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM company_directory_sync)", Boolean.class));
    }

    @Transactional
    public int apply(List<CorpRecord> records) {
        if (records.isEmpty()) throw new IllegalArgumentException("An empty company directory cannot be synchronized");
        // Clear incoming mappings first so stock-code reassignment does not depend on XML ordering.
        jdbc.batchUpdate("UPDATE company SET stock_code = NULL WHERE corp_code = ? AND stock_code IS NOT NULL",
                records, 500, (statement, record) -> statement.setString(1, record.corpCode()));
        var now = OffsetDateTime.now(clock);
        jdbc.batchUpdate("""
                INSERT INTO company (corp_code, stock_code, name, dart_modified_on, created_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (corp_code) DO UPDATE SET stock_code = EXCLUDED.stock_code,
                    name = EXCLUDED.name, dart_modified_on = EXCLUDED.dart_modified_on
                """, records, 500, (statement, record) -> {
            statement.setString(1, record.corpCode());
            statement.setString(2, record.stockCode());
            statement.setString(3, record.corpName());
            statement.setObject(4, record.modifyDate(), Types.DATE);
            statement.setObject(5, now);
        });
        jdbc.update("""
                INSERT INTO company_directory_sync (singleton_id, completed_at, company_count) VALUES (TRUE, ?, ?)
                ON CONFLICT (singleton_id) DO UPDATE SET completed_at = EXCLUDED.completed_at,
                    company_count = EXCLUDED.company_count
                """, now, records.size());
        return records.size();
    }
}
