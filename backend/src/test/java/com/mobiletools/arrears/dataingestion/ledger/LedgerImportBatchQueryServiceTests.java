package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(LedgerImportBatchQueryServiceTests.TestConfig.class)
class LedgerImportBatchQueryServiceTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerImportBatchQueryService batches;

    @BeforeEach
    void prepareSchema() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL, real_name VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE excel_source_file (id BIGINT AUTO_INCREMENT PRIMARY KEY, sha256 VARCHAR(64) NOT NULL, byte_size BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8), original_filename VARCHAR(255) NOT NULL, source_file_id BIGINT, uploaded_by_user_id BIGINT NOT NULL, batch_status VARCHAR(20) NOT NULL, total_rows INT NOT NULL, valid_rows INT NOT NULL, warning_rows INT NOT NULL, error_rows INT NOT NULL, added_rows INT NOT NULL, removed_rows INT NOT NULL, changed_rows INT NOT NULL, unchanged_rows INT NOT NULL, retained_rows INT NOT NULL DEFAULT 0, erased_rows INT NOT NULL DEFAULT 0, original_file_purge_operation_id VARCHAR(36), original_file_purge_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', validation_summary CLOB, uploaded_at TIMESTAMP NOT NULL, confirmed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_current_version (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, batch_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, sheet_name VARCHAR(100) NOT NULL, source_row_number INT NOT NULL, identity_key VARCHAR(255), validation_status VARCHAR(20) NOT NULL, raw_fields CLOB, normalized_fields CLOB, errors CLOB, warnings CLOB)");
        jdbc.execute("CREATE TABLE excel_revision_diff (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, identity_key VARCHAR(255) NOT NULL, change_type VARCHAR(20) NOT NULL, previous_import_row_id BIGINT, candidate_import_row_id BIGINT, previous_fields CLOB, candidate_fields CLOB, changed_fields CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, import_row_id BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT NOT NULL, exception_type VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL)");
        jdbc.execute("CREATE TABLE excel_dataset_period (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, current_version_id BIGINT, PRIMARY KEY(source_type,statistics_key))");
        jdbc.execute("CREATE TABLE excel_dataset_version_row (dataset_version_id BIGINT NOT NULL, identity_key VARCHAR(255) NOT NULL, effective_row_id BIGINT NOT NULL, PRIMARY KEY(dataset_version_id,identity_key))");
        jdbc.execute("CREATE TABLE excel_effective_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, latest_batch_id BIGINT NOT NULL, snapshot_record_id BIGINT NOT NULL)");
        jdbc.execute("CREATE VIEW excel_current_dataset AS SELECT p.source_type,p.statistics_key,e.latest_batch_id AS batch_id,e.snapshot_record_id FROM excel_dataset_period p JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id JOIN excel_effective_row e ON e.id=m.effective_row_id");
    }

    @Test
    void listsSourceAndDateStatusWithCurrentVersionAndPagination() {
        long admin = user("admin");
        long sourceFile = sourceFile("a", 100);
        long current = batch("ARREARS", "202609", "CONFIRMED", "arrears.xlsx", sourceFile, admin, 4, 2, 1, 0);
        long old = batch("ARREARS", "202609", "CONFIRMED", "old.xlsx", sourceFile, admin, 3, 3, 0, 0);
        long unused = batch("ARREARS", "202609", "CONFIRMED", "unused.xlsx", sourceFile, admin, 1, 1, 0, 0);
        long line = batch("LINE_EXPIRY", "20260930", "BLOCKED", "line.xlsx", sourceFile, admin, 0, 0, 0, 1);
        long duplicate = batch("ARREARS", "202609", "DUPLICATE", "duplicate.xlsx", sourceFile, admin, 4, 2, 0, 0);
        long failed = batch("ARREARS", "202609", "FAILED", "failed.xlsx", sourceFile, admin, 4, 2, 0, 1);
        long erased = batch("ARREARS", "202609", "CONFIRMED", "erased.xlsx", null, admin, 2, 2, 0, 0);
        jdbc.update("UPDATE excel_import_batch SET erased_rows=2, original_file_purge_status='PURGED' WHERE id=?", erased);
        jdbc.update("INSERT INTO excel_current_version(source_type,statistics_key,batch_id) VALUES ('ARREARS','202609',?)", current);
        jdbc.update("INSERT INTO excel_dataset_period(source_type,statistics_key,current_version_id) VALUES ('ARREARS','202609',900)");
        jdbc.update("INSERT INTO excel_snapshot_record(batch_id,source_type,import_row_id) VALUES (?,'ARREARS',1)", current);
        jdbc.update("INSERT INTO excel_snapshot_record(batch_id,source_type,import_row_id) VALUES (?,'ARREARS',2)", current);
        jdbc.update("INSERT INTO excel_snapshot_record(batch_id,source_type,import_row_id) VALUES (?,'ARREARS',3)", old);
        List<Long> snapshots = jdbc.queryForList("SELECT id FROM excel_snapshot_record ORDER BY id", Long.class);
        long currentSnapshot1 = snapshots.get(0);
        long currentSnapshot2 = snapshots.get(1);
        long oldSnapshot = snapshots.get(2);
        jdbc.update("INSERT INTO excel_effective_row(latest_batch_id,snapshot_record_id) VALUES (?,?),(?,?),(?,?)",
                current, currentSnapshot1, current, currentSnapshot2, old, oldSnapshot);
        var effectiveRows = jdbc.queryForList("SELECT id FROM excel_effective_row ORDER BY id", Long.class);
        jdbc.update("INSERT INTO excel_dataset_version_row(dataset_version_id,identity_key,effective_row_id) VALUES "
                        + "(900,'202609:A-1',?),(900,'202609:A-2',?),(900,'202609:A-3',?)",
                effectiveRows.get(0), effectiveRows.get(1), effectiveRows.get(2));

        var page = batches.list("ARREARS", "202609", "CONFIRMED", 1, 20);

        assertEquals(4, page.total());
        assertEquals(4, page.items().size());
        assertFalse(page.items().stream().anyMatch(item -> item.batchId() == line));
        var item = page.items().stream().filter(value -> value.batchId() == current).findFirst().orElseThrow();
        assertEquals("admin", item.uploaderUsername());
        assertEquals("a".repeat(64), item.sha256());
        assertEquals(100, item.originalFileSize());
        assertEquals(1, item.warningRows());
        assertEquals(2, item.contributingRows());
        assertEquals("ALL", item.contributionStatus());
        var partial = page.items().stream().filter(value -> value.batchId() == old).findFirst().orElseThrow();
        assertEquals(1, partial.contributingRows());
        assertEquals("PARTIAL", partial.contributionStatus());
        var noContribution = page.items().stream().filter(value -> value.batchId() == unused).findFirst().orElseThrow();
        assertEquals(0, noContribution.contributingRows());
        assertEquals("NONE", noContribution.contributionStatus());
        assertEquals("PARTIAL", batches.detail(old, 1, 20).batch().contributionStatus());
        var allAttempts = batches.list(null, null, null, 1, 20);
        assertEquals(7, allAttempts.total());
        assertTrue(allAttempts.items().stream().anyMatch(attempt -> attempt.batchId() == duplicate && "DUPLICATE".equals(attempt.status())));
        assertTrue(allAttempts.items().stream().anyMatch(attempt -> attempt.batchId() == failed && "FAILED".equals(attempt.status())));
        var erasedBatch = allAttempts.items().stream().filter(attempt -> attempt.batchId() == erased).findFirst().orElseThrow();
        assertFalse(erasedBatch.originalFileAvailable());
        assertEquals(2, erasedBatch.erasedRows());
        assertEquals("PURGED", erasedBatch.originalFilePurgeStatus());
    }

    @Test
    void detailsIncludeSheetsOriginalRowsRawFieldsErrorsWarningsAndRevisionDiff() {
        long admin = user("admin");
        long file = sourceFile("b", 250);
        long batch = batch("ARREARS", "202609", "PRECHECKED", "candidate.xlsx", file, admin, 2, 1, 1, 0);
        jdbc.update("UPDATE excel_import_batch SET validation_summary=? WHERE id=?",
                "{\"issues\":[{\"code\":\"HEADER_NOTICE\",\"severity\":\"WARNING\",\"sheetName\":\"Sheet2\",\"rowNumber\":1}]}", batch);
        jdbc.update("""
                INSERT INTO excel_import_row(batch_id,sheet_name,source_row_number,identity_key,validation_status,raw_fields,normalized_fields,errors,warnings)
                VALUES (?, 'Sheet1', 9, '202609:A-1', 'WARNING', '[{\"columnIndex\":1,\"header\":\"账户ID\",\"value\":\"A-1\"}]', '{\"账户ID\":\"A-1\"}', '[]', '[{\"code\":\"NO_MANAGER\"}]')
                """, batch);
        long importRowId = jdbc.queryForObject("SELECT id FROM excel_import_row WHERE batch_id=?", Long.class, batch);
        jdbc.update("INSERT INTO excel_snapshot_record(batch_id,source_type,import_row_id) VALUES (?,'ARREARS',?)", batch, importRowId);
        long snapshotId = jdbc.queryForObject("SELECT id FROM excel_snapshot_record WHERE import_row_id=?", Long.class, importRowId);
        jdbc.update("INSERT INTO assignment_exception(snapshot_record_id,exception_type,reason,status) VALUES (?,'NO_MATCH','未匹配到经理','OPEN')", snapshotId);
        jdbc.update("INSERT INTO excel_revision_diff(batch_id,identity_key,change_type,previous_import_row_id,candidate_import_row_id) VALUES (?,?,?,NULL,?)",
                batch, "202609:A-1", "ADDED", importRowId);
        jdbc.update("UPDATE excel_import_batch SET added_rows=1, changed_rows=2, unchanged_rows=3, retained_rows=4 WHERE id=?", batch);
        jdbc.update("UPDATE excel_revision_diff SET previous_fields=?,candidate_fields=?,changed_fields=? WHERE batch_id=?",
                "{\"账龄\":\"60天\"}", "{\"账龄\":null}", "[{\"header\":\"账龄\",\"before\":\"60天\",\"after\":null,\"cleared\":true}]", batch);

        var detail = batches.detail(batch, 1, 20);

        assertEquals(batch, detail.batch().batchId());
        assertEquals(2, detail.sheets().size());
        assertTrue(detail.sheets().stream().anyMatch(sheet -> sheet.sheetName().equals("Sheet1") && sheet.rowCount() == 1));
        assertTrue(detail.sheets().stream().anyMatch(sheet -> sheet.sheetName().equals("Sheet2") && sheet.rowCount() == 0));
        assertEquals(9, detail.rows().get(0).sourceRowNumber());
        assertEquals("A-1", detail.rows().get(0).rawFields().get(0).get("value").asText());
        assertEquals("NO_MANAGER", detail.rows().get(0).warnings().get(0).get("code").asText());
        assertEquals("NO_MATCH", detail.rows().get(0).assignmentExceptionType());
        assertEquals("未匹配到经理", detail.rows().get(0).assignmentExceptionReason());
        assertEquals("OPEN", detail.rows().get(0).assignmentExceptionStatus());
        assertEquals("HEADER_NOTICE", detail.validationSummary().get("issues").get(0).get("code").asText());
        assertEquals("ADDED", detail.revisionDifferences().get(0).changeType());
        assertEquals("60天", detail.revisionDifferences().get(0).previousFields().get("账龄").asText());
        assertTrue(detail.revisionDifferences().get(0).candidateFields().get("账龄").isNull());
        assertTrue(detail.revisionDifferences().get(0).changedFields().get(0).get("cleared").asBoolean());
        assertEquals(1, detail.batch().addedRows());
        assertEquals(2, detail.batch().changedRows());
        assertEquals(3, detail.batch().unchangedRows());
        assertEquals(4, detail.batch().retainedRows());
        assertEquals(1, detail.revisionDifferenceTotal());
    }

    @Test
    void rejectsInvalidFiltersAndPageSizes() {
        assertThrows(IllegalArgumentException.class, () -> batches.list("UNKNOWN", null, null, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> batches.list("ARREARS", "20261031", null, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> batches.list(null, null, "NOT_A_STATUS", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> batches.list(null, null, null, 1, 25));
        assertThrows(IllegalArgumentException.class, () -> batches.detail(1, 1, 25));
    }

    @Test
    void unknownBatchIsNotFound() {
        assertThrows(IllegalArgumentException.class, () -> batches.detail(999, 1, 20));
    }

    private long user(String username) {
        jdbc.update("INSERT INTO app_user(username,real_name,role,enabled) VALUES (?,?,'ADMIN',TRUE)", username, "管理员");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private long sourceFile(String prefix, long size) {
        String hash = prefix.repeat(64).substring(0, 64);
        jdbc.update("INSERT INTO excel_source_file(sha256,byte_size) VALUES (?,?)", hash, size);
        return jdbc.queryForObject("SELECT id FROM excel_source_file WHERE sha256=?", Long.class, hash);
    }

    private long batch(String source, String date, String status, String filename, Long sourceFileId,
                       long uploader, int total, int valid, int warnings, int errors) {
        jdbc.update("""
                INSERT INTO excel_import_batch(source_type,statistics_key,original_filename,source_file_id,
                    uploaded_by_user_id,batch_status,total_rows,valid_rows,warning_rows,error_rows,
                    added_rows,removed_rows,changed_rows,unchanged_rows,validation_summary,uploaded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,0,0,0,0,'{}',CURRENT_TIMESTAMP)
                """, source, date, filename, sourceFileId, uploader, status, total, valid, warnings, errors);
        return jdbc.queryForObject("SELECT MAX(id) FROM excel_import_batch", Long.class);
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:ledger-batch-queries;MODE=MySQL;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean LedgerImportBatchQueryService queryService(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new LedgerImportBatchQueryService(jdbc, mapper);
        }
        @Bean org.springframework.transaction.PlatformTransactionManager transactionManager(DataSource source) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        }
    }
}
