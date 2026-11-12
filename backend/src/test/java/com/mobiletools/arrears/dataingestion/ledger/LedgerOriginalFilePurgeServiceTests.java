package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LedgerOriginalFilePurgeServiceTests {

    @TempDir Path tempDir;
    private JdbcTemplate jdbc;
    private LedgerOriginalFilePurgeService purges;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:ledger-file-purges;MODE=MySQL;DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        DataSource dataSource = source;
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE excel_original_file_purge_queue (id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id VARCHAR(36) NOT NULL, storage_key VARCHAR(80) NOT NULL, attempt_count INT NOT NULL DEFAULT 0, last_error_code VARCHAR(60), next_attempt_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_attempt_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_source_file (id BIGINT AUTO_INCREMENT PRIMARY KEY, storage_key VARCHAR(80) NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, original_file_purge_operation_id VARCHAR(36), original_file_purge_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE')");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_type VARCHAR(60), result VARCHAR(30), detail VARCHAR(2000))");
        purges = new LedgerOriginalFilePurgeService(jdbc,
                new LedgerOriginalWorkbookStore(tempDir.toString()),
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    }

    @Test
    void absentFileIsIdempotentlyTreatedAsPurged() {
        String operationId = "operation-a";
        jdbc.update("INSERT INTO excel_import_batch(original_file_purge_operation_id,original_file_purge_status) VALUES (?, 'PENDING')",
                operationId);
        jdbc.update("INSERT INTO excel_original_file_purge_queue(operation_id,storage_key) VALUES (?,?)",
                operationId, "aa/" + "a".repeat(64));

        var result = purges.processOperation(operationId);

        assertEquals(1, result.deletedFiles());
        assertEquals(0, result.pendingFiles());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_original_file_purge_queue", Integer.class));
        assertEquals("PURGED", jdbc.queryForObject("SELECT original_file_purge_status FROM excel_import_batch", String.class));
    }

    @Test
    void invalidStorageKeyStaysPendingWithSanitizedRetryState() {
        String operationId = "operation-b";
        jdbc.update("INSERT INTO excel_import_batch(original_file_purge_operation_id,original_file_purge_status) VALUES (?, 'PENDING')",
                operationId);
        jdbc.update("INSERT INTO excel_original_file_purge_queue(operation_id,storage_key) VALUES (?,?)",
                operationId, "../outside-file");
        jdbc.update("INSERT INTO operation_audit_log(operation_type,result,detail) VALUES ('LEDGER_RECORD_ERASURE','PENDING_FILE_PURGE',?)",
                "删除；原件状态=待重试；原件操作=" + operationId);

        var result = purges.processOperation(operationId);

        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.pendingFiles());
        assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM excel_original_file_purge_queue", Integer.class));
        assertEquals("FILESYSTEM_IO", jdbc.queryForObject("SELECT last_error_code FROM excel_original_file_purge_queue", String.class));
        assertFalse(jdbc.queryForObject("SELECT last_error_code FROM excel_original_file_purge_queue", String.class)
                .contains("outside-file"));
        assertEquals("PENDING", jdbc.queryForObject("SELECT original_file_purge_status FROM excel_import_batch", String.class));

        jdbc.update("UPDATE excel_original_file_purge_queue SET storage_key=? WHERE operation_id=?",
                "bb/" + "b".repeat(64), operationId);
        var retry = purges.processOperation(operationId);

        assertEquals(1, retry.deletedFiles());
        assertEquals(0, retry.pendingFiles());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_original_file_purge_queue", Integer.class));
        assertEquals("PURGED", jdbc.queryForObject("SELECT original_file_purge_status FROM excel_import_batch", String.class));
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT result FROM operation_audit_log WHERE operation_type='LEDGER_RECORD_ERASURE'", String.class));
        assertEquals("删除；原件状态=已清理；原件操作=" + operationId,
                jdbc.queryForObject("SELECT detail FROM operation_audit_log WHERE operation_type='LEDGER_RECORD_ERASURE'", String.class));
    }
}
