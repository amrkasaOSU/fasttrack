package com.fasttrack.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasttrack.support.AbstractIntegrationTest;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the Flyway baseline applies cleanly to a real PostgreSQL 16 and that
 * the entities validate against it (the context would not start otherwise,
 * because {@code ddl-auto} is {@code validate}).
 */
class SchemaMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    DataSource dataSource;

    @Test
    @DisplayName("every table the application needs exists after migration")
    void allTablesExist() throws Exception {
        List<String> tables = query(
                "select table_name from information_schema.tables where table_schema = 'public'");

        assertThat(tables).contains(
                "users", "gmail_accounts", "applications", "application_events",
                "email_messages", "email_classifications", "email_corrections",
                "resumes", "job_postings", "fit_checks", "sync_runs", "llm_invocations",
                "flyway_schema_history");
    }

    @Test
    @DisplayName("a Gmail message id can only be stored once per user (idempotent sync)")
    void gmailMessageIdIsUniquePerUser() throws Exception {
        List<String> indexes = query(
                "select indexname from pg_indexes where tablename = 'email_messages'");

        assertThat(indexes).contains("ux_email_user_gmail_id");
    }

    @Test
    @DisplayName("application status values are constrained by the database, not only by code")
    void statusCheckConstraintExists() throws Exception {
        List<String> constraints = query(
                "select conname from pg_constraint where conrelid = 'applications'::regclass");

        assertThat(constraints).contains("ck_applications_status", "ck_applications_source");
    }

    @Test
    @DisplayName("the migration is recorded as successful")
    void migrationSucceeded() throws Exception {
        List<String> state = query(
                "select success::text from flyway_schema_history where version = '1'");

        assertThat(state).containsExactly("true");
    }

    private List<String> query(String sql) throws Exception {
        List<String> values = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
