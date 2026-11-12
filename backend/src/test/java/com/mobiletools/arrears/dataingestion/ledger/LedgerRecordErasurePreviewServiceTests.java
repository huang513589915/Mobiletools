package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerRecordErasurePreviewServiceTests {
    private JdbcTemplate jdbc;
    private LedgerRecordErasurePreviewService previews;
    private LedgerErasurePreviewRegistry registry;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:ledger-erasure-preview;MODE=MySQL;DB_CLOSE_DELAY=-1");
        source.setUser("sa"); source.setPassword("");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE excel_source_file(id BIGINT PRIMARY KEY, storage_key VARCHAR(80), sha256 VARCHAR(64), byte_size BIGINT)");
        jdbc.execute("CREATE TABLE excel_import_batch(id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), original_filename VARCHAR(255), source_file_id BIGINT)");
        jdbc.execute("CREATE TABLE excel_import_row(id BIGINT PRIMARY KEY, batch_id BIGINT, identity_key VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_snapshot_record(id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), import_row_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_snapshot(snapshot_record_id BIGINT PRIMARY KEY, account_id VARCHAR(100), group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE line_expiry_snapshot(snapshot_record_id BIGINT PRIMARY KEY, member_service_number VARCHAR(100), group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_dataset_period(source_type VARCHAR(20), statistics_key VARCHAR(8), current_version_id BIGINT)");
        jdbc.execute("CREATE TABLE excel_dataset_version(id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8))");
        jdbc.execute("CREATE TABLE excel_effective_row(id BIGINT PRIMARY KEY, snapshot_record_id BIGINT)");
        jdbc.execute("CREATE TABLE excel_dataset_version_row(dataset_version_id BIGINT, identity_key VARCHAR(255), effective_row_id BIGINT)");
        jdbc.execute("CREATE VIEW excel_current_dataset AS SELECT p.source_type,p.statistics_key,p.current_version_id,e.snapshot_record_id,m.identity_key FROM excel_dataset_period p JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id JOIN excel_effective_row e ON e.id=m.effective_row_id");
        jdbc.execute("CREATE TABLE excel_revision_diff(id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT, identity_key VARCHAR(255))");
        jdbc.execute("CREATE TABLE assignment_exception(id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT)");
        jdbc.execute("CREATE TABLE assignment_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_work_order(id BIGINT PRIMARY KEY, statistics_key VARCHAR(8), account_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE arrears_work_order_status_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_work_order_follow_up(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT)");
        jdbc.execute("CREATE TABLE line_renewal_work_order(id BIGINT PRIMARY KEY, statistics_key VARCHAR(8), member_service_number VARCHAR(100))");
        jdbc.execute("CREATE TABLE line_renewal_status_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT)");
        jdbc.execute("CREATE TABLE line_renewal_work_order_follow_up(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT)");
        registry = new LedgerErasurePreviewRegistry();
        previews = new LedgerRecordErasurePreviewService(jdbc, registry);
    }

    @Test
    void arrearsPreviewCountsPeriodHistoryAndSharedWorkbookOtherKeys() {
        jdbc.update("INSERT INTO excel_source_file VALUES (1, ?, ?, 10)", "aa/" + "a".repeat(64), "a".repeat(64));
        jdbc.update("INSERT INTO excel_import_batch VALUES (1,'ARREARS','202611','first.xlsx',1),(2,'ARREARS','202611','second.xlsx',1),(3,'LINE_EXPIRY','20261111','mixed.xlsx',1)");
        jdbc.update("INSERT INTO excel_import_row VALUES (11,1,'202611:A-1'),(12,2,'202611:A-1'),(13,2,'202611:B-2'),(14,3,'20261111:S-1')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (101,1,'ARREARS',11),(102,2,'ARREARS',12),(103,2,'ARREARS',13),(104,3,'LINE_EXPIRY',14)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES (101,'A-1','G1','Group one'),(102,'A-1','G1','Group one'),(103,'B-2','G2','Group two')");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES (104,'S-1','G3','Group three')");
        jdbc.update("INSERT INTO excel_dataset_period VALUES ('ARREARS','202611',9),('LINE_EXPIRY','20261111',10)");
        jdbc.update("INSERT INTO excel_dataset_version VALUES (9,'ARREARS','202611'),(10,'LINE_EXPIRY','20261111')");
        jdbc.update("INSERT INTO excel_effective_row VALUES (901,102),(1001,104)");
        jdbc.update("INSERT INTO excel_dataset_version_row VALUES (9,'202611:A-1',901),(10,'20261111:S-1',1001)");
        jdbc.update("INSERT INTO excel_revision_diff(batch_id,identity_key) VALUES (1,'202611:A-1')");
        jdbc.update("INSERT INTO assignment_exception(snapshot_record_id) VALUES (102)");
        jdbc.update("INSERT INTO assignment_history(snapshot_record_id) VALUES (102)");
        jdbc.update("INSERT INTO arrears_work_order VALUES (201,'202611','A-1')");
        jdbc.update("INSERT INTO arrears_work_order_status_history(work_order_id) VALUES (201),(201)");
        jdbc.update("INSERT INTO arrears_work_order_follow_up(work_order_id) VALUES (201)");

        var result = previews.preview(LedgerWorkbookParser.SourceType.ARREARS, java.util.List.of(102L), "admin");

        assertEquals(1, result.selectedRecords().size());
        assertEquals(2, result.periods().get(0).historicalImportRows());
        assertEquals(2, result.periods().get(0).affectedBatches());
        assertEquals(2, result.periods().get(0).snapshots());
        assertEquals(1, result.periods().get(0).versions());
        assertEquals(1, result.periods().get(0).revisionDiffs());
        assertEquals(1, result.periods().get(0).assignmentExceptions());
        assertEquals(1, result.periods().get(0).assignmentHistory());
        assertEquals(1, result.periods().get(0).workOrders());
        assertEquals(2, result.periods().get(0).workOrderStatusChanges());
        assertEquals(1, result.periods().get(0).workOrderFollowUps());
        assertEquals(3, result.originalFiles().get(0).referenceBatches());
        assertEquals(3, result.originalFiles().get(0).parsedBusinessKeys());
        assertEquals(2, result.originalFiles().get(0).otherBusinessKeys());
        assertEquals(2, result.originalFiles().get(0).affectedPeriods());
        assertEquals("A-1", result.selectedRecords().get(0).stableIdentity());
        assertEquals("Group one", result.selectedRecords().get(0).groupName());
    }

    @Test
    void rejectsMissingCurrentSnapshotsDuplicateIdsAndOversizedSelection() {
        assertThrows(IllegalArgumentException.class, () -> previews.preview(
                LedgerWorkbookParser.SourceType.ARREARS, java.util.List.of(999L), "admin"));
        assertThrows(IllegalArgumentException.class, () -> previews.preview(
                LedgerWorkbookParser.SourceType.ARREARS, java.util.List.of(1L, 1L), "admin"));
        assertThrows(IllegalArgumentException.class, () -> previews.preview(
                LedgerWorkbookParser.SourceType.ARREARS,
                java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList(), "admin"));
    }

    @Test
    void linePreviewCountsRenewalWorkOrdersAndHistory() {
        jdbc.update("INSERT INTO excel_import_batch VALUES (3,'LINE_EXPIRY','20261111','lines.xlsx',NULL)");
        jdbc.update("INSERT INTO excel_import_row VALUES (13,3,'20261111:S-1')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (103,3,'LINE_EXPIRY',13)");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES (103,'S-1','G3','Group three')");
        jdbc.update("INSERT INTO excel_dataset_period VALUES ('LINE_EXPIRY','20261111',10)");
        jdbc.update("INSERT INTO excel_dataset_version VALUES (10,'LINE_EXPIRY','20261111')");
        jdbc.update("INSERT INTO excel_effective_row VALUES (1001,103)");
        jdbc.update("INSERT INTO excel_dataset_version_row VALUES (10,'20261111:S-1',1001)");
        jdbc.update("INSERT INTO line_renewal_work_order VALUES (401,'20261111','S-1')");
        jdbc.update("INSERT INTO line_renewal_status_history(work_order_id) VALUES (401)");
        jdbc.update("INSERT INTO line_renewal_work_order_follow_up(work_order_id) VALUES (401)");

        var result = previews.preview(LedgerWorkbookParser.SourceType.LINE_EXPIRY, java.util.List.of(103L), "admin");

        assertEquals(1, result.periods().get(0).workOrders());
        assertEquals(1, result.periods().get(0).workOrderStatusChanges());
        assertEquals(1, result.periods().get(0).workOrderFollowUps());
    }
}
