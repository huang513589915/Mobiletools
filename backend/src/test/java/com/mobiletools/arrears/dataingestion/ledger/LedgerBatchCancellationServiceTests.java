package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(LedgerBatchCancellationServiceTests.TestConfig.class)
class LedgerBatchCancellationServiceTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerBatchCancellationService cancellations;

    @BeforeEach
    void prepareSchema() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8), batch_status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, raw_fields CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT NOT NULL, operation_type VARCHAR(100) NOT NULL, target_type VARCHAR(100) NOT NULL, target_id BIGINT, result VARCHAR(30) NOT NULL, detail VARCHAR(1000))");
        jdbc.update("INSERT INTO app_user(username, role, enabled) VALUES ('admin', 'ADMIN', TRUE)");
    }

    @Test
    void cancelsOnlyTheCandidateAndKeepsItsPreviewAndAuditTrail() {
        long batchId = batch("PRECHECKED");
        jdbc.update("INSERT INTO excel_import_row(batch_id, raw_fields) VALUES (?, ?)", batchId, "original row");

        var result = cancellations.cancel(batchId, "admin");

        assertEquals(new LedgerBatchCancellationService.CancellationResult(
                batchId, "CANCELLED", "ARREARS", "202609"), result);
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, batchId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_row WHERE batch_id=?", Integer.class, batchId));
        assertEquals("original row", jdbc.queryForObject("SELECT raw_fields FROM excel_import_row WHERE batch_id=?", String.class, batchId));
        assertEquals("LEDGER_IMPORT_CANCEL", jdbc.queryForObject("SELECT operation_type FROM operation_audit_log WHERE target_id=?", String.class, batchId));
    }

    @Test
    void cannotCancelConfirmedOrMissingBatches() {
        long confirmed = batch("CONFIRMED");

        assertThrows(IllegalArgumentException.class, () -> cancellations.cancel(confirmed, "admin"));
        assertThrows(IllegalArgumentException.class, () -> cancellations.cancel(999, "admin"));
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, confirmed));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log", Integer.class));
    }

    @Test
    void requiresAnEnabledAdministrator() {
        long batchId = batch("PRECHECKED");
        jdbc.update("INSERT INTO app_user(username, role, enabled) VALUES ('manager', 'CUSTOMER_MANAGER', TRUE)");
        jdbc.update("INSERT INTO app_user(username, role, enabled) VALUES ('disabled-admin', 'ADMIN', FALSE)");

        assertThrows(IllegalArgumentException.class, () -> cancellations.cancel(batchId, "manager"));
        assertThrows(IllegalArgumentException.class, () -> cancellations.cancel(batchId, "disabled-admin"));
        assertEquals("PRECHECKED", jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, batchId));
    }

    private long batch(String status) {
        jdbc.update("INSERT INTO excel_import_batch(source_type, statistics_key, batch_status) VALUES ('ARREARS', '202609', ?)", status);
        return jdbc.queryForObject("SELECT MAX(id) FROM excel_import_batch", Long.class);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:ledger-batch-cancellation;MODE=MySQL;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean LedgerBatchCancellationService cancellationService(JdbcTemplate jdbc) {
            return new LedgerBatchCancellationService(jdbc);
        }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(DataSource source) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        }
    }
}
