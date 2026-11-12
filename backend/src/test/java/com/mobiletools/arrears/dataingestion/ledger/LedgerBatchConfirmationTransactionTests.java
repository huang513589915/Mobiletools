package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(LedgerBatchConfirmationTransactionTests.TestConfig.class)
class LedgerBatchConfirmationTransactionTests {

    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerBatchConfirmationService confirmations;

    @BeforeEach
    void prepareSchema() {
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL, real_name VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8), batch_status VARCHAR(20) NOT NULL, total_rows INT NOT NULL, valid_rows INT NOT NULL DEFAULT 0, warning_rows INT NOT NULL DEFAULT 0, error_rows INT NOT NULL DEFAULT 0, added_rows INT NOT NULL DEFAULT 0, removed_rows INT NOT NULL DEFAULT 0, changed_rows INT NOT NULL DEFAULT 0, unchanged_rows INT NOT NULL DEFAULT 0, retained_rows INT NOT NULL DEFAULT 0, preview_base_version_id BIGINT, revision_of_batch_id BIGINT, validation_summary CLOB, confirmed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, sheet_name VARCHAR(100) NOT NULL, source_row_number INT NOT NULL, identity_key VARCHAR(255), validation_status VARCHAR(20) NOT NULL, normalized_fields CLOB, raw_fields CLOB NOT NULL, warnings CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, import_row_id BIGINT NOT NULL UNIQUE, excel_manager_name VARCHAR(255), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, account_id VARCHAR(100) NOT NULL, group_code VARCHAR(100) NOT NULL, group_name VARCHAR(255) NOT NULL, carrying_product_name VARCHAR(255), ending_arrears_amount DECIMAL(20,4) NOT NULL, aging_value VARCHAR(100), UNIQUE(batch_id, account_id))");
        jdbc.execute("CREATE TABLE arrears_work_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, statistics_key VARCHAR(6) NOT NULL, account_id VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT UNIQUE, status VARCHAR(20) NOT NULL DEFAULT '待处理', last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP, UNIQUE(statistics_key, account_id))");
        jdbc.execute("CREATE TABLE arrears_work_order_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CHECK(previous_status <> new_status))");
        jdbc.execute("CREATE TABLE arrears_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, note CLOB NOT NULL, created_by_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, member_service_number VARCHAR(100) NOT NULL, group_code VARCHAR(100) NOT NULL, group_name VARCHAR(255) NOT NULL, product_code VARCHAR(100), product_name VARCHAR(255), billing_cutoff_date DATE NOT NULL, UNIQUE(batch_id, member_service_number))");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT NOT NULL, exception_type VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) DEFAULT 'OPEN', resolution VARCHAR(1000), handled_by_user_id BIGINT, handled_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE assignment_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT NOT NULL, previous_manager_user_id BIGINT, new_manager_user_id BIGINT NOT NULL, change_type VARCHAR(30) NOT NULL, changed_by_user_id BIGINT NOT NULL, reason VARCHAR(1000), changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_current_version (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, batch_id BIGINT NOT NULL UNIQUE, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_revision_diff (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, identity_key VARCHAR(255) NOT NULL, change_type VARCHAR(20) NOT NULL, previous_import_row_id BIGINT, candidate_import_row_id BIGINT, previous_fields CLOB, candidate_fields CLOB, changed_fields CLOB)");
        jdbc.execute("CREATE TABLE line_renewal_work_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, statistics_key VARCHAR(8) NOT NULL, member_service_number VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT UNIQUE, status VARCHAR(20) NOT NULL DEFAULT '待处理', last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(statistics_key, member_service_number), CHECK(status IN ('待处理','跟进中','已续签','不续签')))");
        jdbc.execute("CREATE TABLE line_renewal_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CHECK(previous_status <> new_status))");
        jdbc.execute("CREATE TABLE line_renewal_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, note CLOB NOT NULL, created_by_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_dataset_version (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, source_batch_id BIGINT NOT NULL UNIQUE, based_on_version_id BIGINT, created_by_user_id BIGINT NOT NULL, added_rows INT NOT NULL, updated_rows INT NOT NULL, unchanged_rows INT NOT NULL, retained_rows INT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_dataset_period (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, current_version_id BIGINT, changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(source_type,statistics_key))");
        jdbc.execute("CREATE TABLE excel_effective_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, identity_key VARCHAR(255) NOT NULL, latest_batch_id BIGINT NOT NULL, snapshot_record_id BIGINT NOT NULL UNIQUE, raw_fields CLOB NOT NULL, normalized_fields CLOB NOT NULL, field_provenance CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE excel_dataset_version_row (dataset_version_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, identity_key VARCHAR(255) NOT NULL, effective_row_id BIGINT NOT NULL, PRIMARY KEY(dataset_version_id,identity_key), UNIQUE(dataset_version_id,effective_row_id))");
        jdbc.execute("CREATE VIEW excel_current_dataset AS SELECT p.source_type,p.statistics_key,p.current_version_id,e.latest_batch_id AS batch_id,e.snapshot_record_id,e.id AS effective_row_id,m.identity_key,e.raw_fields AS effective_raw_fields,e.normalized_fields AS effective_normalized_fields,e.field_provenance,p.changed_by_user_id,p.changed_at FROM excel_dataset_period p JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id JOIN excel_effective_row e ON e.id=m.effective_row_id");
    }

    @Test
    void confirmsArrearsAndRecordsUnmatchedManagerWithoutBlocking() {
        long admin = user("admin", "系统管理员", "ADMIN");
        long manager = user("manager", "张伟", "CUSTOMER_MANAGER");
        long batch = batch("ARREARS", "202609", 3);
        importRow(batch, 2, arrears("A-1", "张伟", "产品甲"));
        importRow(batch, 3, arrears("A-2", "不存在的经理", null));
        importRow(batch, 4, arrears("A-3", "重名经理", ""));
        user("manager-duplicate", "重名经理", "CUSTOMER_MANAGER");
        user("manager-duplicate-two", "重名经理", "CUSTOMER_MANAGER");

        var result = confirmations.confirm(batch, "admin");

        assertEquals(3, result.confirmedRows());
        assertEquals(2, result.assignmentExceptionRows());
        assertEquals(2, result.warningRows());
        assertEquals(3, count("excel_snapshot_record"));
        assertEquals(3, count("arrears_snapshot"));
        assertEquals(3, count("arrears_work_order"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM arrears_work_order WHERE status='待处理' AND last_handled_by_user_id IS NULL AND last_handled_at IS NULL", Integer.class));
        assertEquals(0, count("line_expiry_snapshot"));
        assertEquals(2, count("assignment_exception"));
        assertEquals(1, count("assignment_history"));
        assertEquals(manager, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE import_row_id=(SELECT MIN(id) FROM excel_import_row)", Long.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE import_row_id=(SELECT MAX(id) FROM excel_import_row)", Long.class));
        assertCurrent("ARREARS", "202609", batch);
        assertEquals("CONFIRMED", batchStatus(batch));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_exception WHERE exception_type='NO_MATCH'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_exception WHERE exception_type='MULTIPLE_MATCHES'", Integer.class));
        String retainedRawFields = jdbc.queryForObject("""
                SELECT r.raw_fields FROM excel_import_row r
                JOIN excel_snapshot_record s ON s.import_row_id=r.id
                WHERE s.excel_manager_name='张伟'
                """, String.class);
        assertTrue(retainedRawFields.contains("张伟"));
        assertTrue(retainedRawFields.contains("集团编码"));
        assertEquals(2, jdbc.queryForObject("SELECT warning_rows FROM excel_import_batch WHERE id=?", Integer.class, batch));
        assertEquals(admin, jdbc.queryForObject("SELECT changed_by_user_id FROM excel_current_version WHERE source_type='ARREARS'", Long.class));
    }

    @Test
    void confirmsLineExpiryAndKeepsItsCurrentVersionIndependent() {
        user("admin", "系统管理员", "ADMIN");
        long manager = user("manager", "李明", "CUSTOMER_MANAGER");
        long batch = batch("LINE_EXPIRY", "20260901", 1);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("统计日期", "20260901");
        fields.put("集团编码", "G-1");
        fields.put("集团名称", "集团一");
        fields.put("成员服务号码", "S-1");
        fields.put("客户经理姓名", "李明");
        fields.put("计费截止日期", "2026-10-31");
        fields.put("产品编码", null);
        fields.put("产品名称", null);
        importRow(batch, 2, fields);

        var result = confirmations.confirm(batch, "admin");

        assertEquals(1, result.confirmedRows());
        assertEquals(0, result.assignmentExceptionRows());
        assertEquals(1, count("line_expiry_snapshot"));
        assertEquals(0, count("arrears_snapshot"));
        assertEquals("2026-10-31", jdbc.queryForObject("SELECT CAST(billing_cutoff_date AS VARCHAR) FROM line_expiry_snapshot", String.class));
        assertNull(jdbc.queryForObject("SELECT product_code FROM line_expiry_snapshot", String.class));
        assertEquals(manager, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record", Long.class));
        assertCurrent("LINE_EXPIRY", "20260901", batch);
        assertEquals(1, count("line_renewal_work_order"));
        assertEquals("待处理", jdbc.queryForObject("SELECT status FROM line_renewal_work_order", String.class));
        assertNull(jdbc.queryForObject("SELECT last_handled_by_user_id FROM line_renewal_work_order", Long.class));
        assertNull(jdbc.queryForObject("SELECT last_handled_at FROM line_renewal_work_order", java.sql.Timestamp.class));
    }

    @Test
    void lineRevisionReusesWorkOrderAndPreservesHandlingFieldsAndHistory() {
        user("admin", "系统管理员", "ADMIN");
        user("manager", "李明", "CUSTOMER_MANAGER");
        long original = batch("LINE_EXPIRY", "20260901", 1);
        importRow(original, 2, lineExpiry("S-REV", "旧产品"));
        confirmations.confirm(original, "admin");
        long workOrderId = jdbc.queryForObject("SELECT id FROM line_renewal_work_order", Long.class);
        long managerId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='manager'", Long.class);
        jdbc.update("UPDATE line_renewal_work_order SET status='跟进中',last_handled_by_user_id=?,last_handled_at=CURRENT_TIMESTAMP WHERE id=?", managerId, workOrderId);
        jdbc.update("INSERT INTO line_renewal_status_history(work_order_id,previous_status,new_status,changed_by_user_id) VALUES (?, '待处理','跟进中',?)", workOrderId, managerId);
        jdbc.update("INSERT INTO line_renewal_work_order_follow_up(work_order_id,note,created_by_user_id) VALUES (?, '已电话联系客户', ?)", workOrderId, managerId);
        java.sql.Timestamp handledAt = jdbc.queryForObject("SELECT last_handled_at FROM line_renewal_work_order WHERE id=?", java.sql.Timestamp.class, workOrderId);

        long revision = batch("LINE_EXPIRY", "20260901", 1);
        importRow(revision, 2, lineExpiry("S-REV", "修正版产品"));
        confirmations.confirm(revision, "admin");

        assertEquals(1, count("line_renewal_work_order"));
        assertEquals(workOrderId, jdbc.queryForObject("SELECT id FROM line_renewal_work_order", Long.class));
        assertEquals(revision, jdbc.queryForObject("SELECT batch_id FROM line_expiry_snapshot WHERE snapshot_record_id=(SELECT current_snapshot_record_id FROM line_renewal_work_order WHERE id=?)", Long.class, workOrderId));
        assertEquals("跟进中", jdbc.queryForObject("SELECT status FROM line_renewal_work_order WHERE id=?", String.class, workOrderId));
        assertEquals(managerId, jdbc.queryForObject("SELECT last_handled_by_user_id FROM line_renewal_work_order WHERE id=?", Long.class, workOrderId));
        assertEquals(handledAt, jdbc.queryForObject("SELECT last_handled_at FROM line_renewal_work_order WHERE id=?", java.sql.Timestamp.class, workOrderId));
        assertEquals(1, count("line_renewal_status_history"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_work_order_follow_up WHERE work_order_id=?", Integer.class, workOrderId));
    }

    @Test
    void lineRevisionAddsAndRetainsRowsWithoutLosingWorkOrderHistory() {
        user("admin", "系统管理员", "ADMIN");
        long managerId = user("manager", "李明", "CUSTOMER_MANAGER");
        long original = batch("LINE_EXPIRY", "20260901", 2);
        importRow(original, 2, lineExpiry("S-KEEP", "旧产品"));
        importRow(original, 3, lineExpiry("S-REMOVE", "待删除产品"));
        confirmations.confirm(original, "admin");

        long removedOrderId = jdbc.queryForObject("""
                SELECT id FROM line_renewal_work_order WHERE member_service_number='S-REMOVE'
                """, Long.class);
        jdbc.update("UPDATE line_renewal_work_order SET status='已续签',last_handled_by_user_id=?,last_handled_at=CURRENT_TIMESTAMP WHERE id=?",
                managerId, removedOrderId);
        jdbc.update("""
                INSERT INTO line_renewal_status_history(work_order_id,previous_status,new_status,changed_by_user_id)
                VALUES (?, '待处理','已续签',?)
                """, removedOrderId, managerId);
        jdbc.update("INSERT INTO line_renewal_work_order_follow_up(work_order_id,note,created_by_user_id) VALUES (?, '确认客户继续使用', ?)", removedOrderId, managerId);

        long firstRevision = batch("LINE_EXPIRY", "20260901", 2);
        importRow(firstRevision, 2, lineExpiry("S-KEEP", "修正版保留产品"));
        importRow(firstRevision, 3, lineExpiry("S-NEW", "修正版新增产品"));
        confirmations.confirm(firstRevision, "admin");

        assertEquals(3, count("line_renewal_work_order"));
        assertEquals("待处理", jdbc.queryForObject("SELECT status FROM line_renewal_work_order WHERE member_service_number='S-NEW'", String.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_current_dataset
                WHERE source_type='LINE_EXPIRY' AND statistics_key='20260901'
                """, Integer.class));
        assertEquals(original, jdbc.queryForObject("""
                SELECT batch_id FROM excel_current_dataset
                WHERE source_type='LINE_EXPIRY' AND statistics_key='20260901'
                  AND identity_key='20260901:S-REMOVE'
                """, Long.class));
        assertEquals(removedOrderId, jdbc.queryForObject("""
                SELECT id FROM line_renewal_work_order
                WHERE member_service_number='S-REMOVE' AND current_snapshot_record_id=(
                    SELECT snapshot_record_id FROM excel_current_dataset WHERE identity_key='20260901:S-REMOVE'
                )
                """, Long.class));

        long secondRevision = batch("LINE_EXPIRY", "20260901", 3);
        importRow(secondRevision, 2, lineExpiry("S-KEEP", "再次修正版保留产品"));
        importRow(secondRevision, 3, lineExpiry("S-NEW", "再次修正版新增产品"));
        importRow(secondRevision, 4, lineExpiry("S-REMOVE", "重新出现产品"));
        confirmations.confirm(secondRevision, "admin");

        assertEquals(removedOrderId, jdbc.queryForObject(
                "SELECT id FROM line_renewal_work_order WHERE member_service_number='S-REMOVE'", Long.class));
        assertEquals("已续签", jdbc.queryForObject(
                "SELECT status FROM line_renewal_work_order WHERE id=?", String.class, removedOrderId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_renewal_status_history WHERE work_order_id=?", Integer.class, removedOrderId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM line_renewal_work_order_follow_up WHERE work_order_id=?", Integer.class, removedOrderId));
        assertEquals(secondRevision, jdbc.queryForObject("""
                SELECT l.batch_id FROM line_renewal_work_order w
                JOIN line_expiry_snapshot l ON l.snapshot_record_id=w.current_snapshot_record_id
                WHERE w.id=?
                """, Long.class, removedOrderId));
    }

    @Test
    void concurrentLineRevisionConfirmationsKeepOneWorkOrderAndConsistentBatchState() throws Exception {
        user("admin", "系统管理员", "ADMIN");
        user("manager", "李明", "CUSTOMER_MANAGER");
        long first = batch("LINE_EXPIRY", "20260901", 1);
        long second = batch("LINE_EXPIRY", "20260901", 1);
        importRow(first, 2, lineExpiry("S-CONCURRENT", "并发版本一"));
        importRow(second, 2, lineExpiry("S-CONCURRENT", "并发版本二"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var firstConfirmation = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                return confirmations.confirm(first, "admin");
            });
            var secondConfirmation = pool.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                return confirmations.confirm(second, "admin");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var firstResult = firstConfirmation.get(10, TimeUnit.SECONDS);
            var secondResult = secondConfirmation.get(10, TimeUnit.SECONDS);
            assertEquals(first, firstResult.batchId());
            assertEquals(second, secondResult.batchId());
            assertNotEquals(firstResult.confirmationRequired(), secondResult.confirmationRequired());

            long staleBatch = firstResult.confirmationRequired() ? first : second;
            assertEquals("PRECHECKED", batchStatus(staleBatch));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM excel_import_batch WHERE batch_status='CONFIRMED'", Integer.class));
            var freshConfirmation = confirmations.confirm(staleBatch, "admin");
            assertTrue(freshConfirmation.currentVersion());
            assertFalse(freshConfirmation.confirmationRequired());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("line_renewal_work_order"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch WHERE batch_status='CONFIRMED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM excel_current_version WHERE source_type='LINE_EXPIRY' AND statistics_key='20260901'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_expiry_snapshot WHERE batch_id=(SELECT batch_id FROM excel_current_version WHERE source_type='LINE_EXPIRY' AND statistics_key='20260901')", Integer.class));
    }

    @Test
    void laterSnapshotDoesNotInheritManualAssignmentAndRetainsExcelManagerName() {
        user("admin", "系统管理员", "ADMIN");
        long previouslyAssignedManager = user("previous-manager", "手动归属经理", "CUSTOMER_MANAGER");
        long original = batch("ARREARS", "202609", 1);
        importRow(original, 2, arrears("A-1", "Excel姓名无匹配", "产品甲"));
        confirmations.confirm(original, "admin");
        jdbc.update("UPDATE excel_snapshot_record SET effective_manager_user_id=? WHERE batch_id=?",
                previouslyAssignedManager, original);

        long later = batch("ARREARS", "202610", 1);
        importRow(later, 2, arrears("A-1", "Excel姓名无匹配", "产品乙"));
        confirmations.confirm(later, "admin");

        assertEquals(previouslyAssignedManager, jdbc.queryForObject(
                "SELECT effective_manager_user_id FROM excel_snapshot_record WHERE batch_id=?", Long.class, original));
        assertNull(jdbc.queryForObject(
                "SELECT effective_manager_user_id FROM excel_snapshot_record WHERE batch_id=?", Long.class, later));
        assertEquals("Excel姓名无匹配", jdbc.queryForObject(
                "SELECT excel_manager_name FROM excel_snapshot_record WHERE batch_id=?", String.class, later));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_exception WHERE snapshot_record_id="
                + "(SELECT id FROM excel_snapshot_record WHERE batch_id=?) AND exception_type='NO_MATCH'", Integer.class, later));
    }

    @Test
    void revisionKeepsManualOwnerWhenExcelNameIsStableAndRematchesWhenItChanges() {
        user("admin", "系统管理员", "ADMIN");
        long manuallyAssigned = user("manager-manual", "手动归属经理", "CUSTOMER_MANAGER");
        long rematched = user("manager-new", "新经理", "CUSTOMER_MANAGER");
        long initial = batch("ARREARS", "202609", 1);
        importRow(initial, 2, arrears("A-OWNER", "未匹配的Excel姓名", "原产品"));
        confirmations.confirm(initial, "admin");
        long initialSnapshot = jdbc.queryForObject("SELECT id FROM excel_snapshot_record WHERE batch_id=?", Long.class, initial);
        jdbc.update("UPDATE excel_snapshot_record SET effective_manager_user_id=? WHERE id=?", manuallyAssigned, initialSnapshot);
        jdbc.update("UPDATE assignment_exception SET status='RESOLVED',resolution='管理员分配',handled_by_user_id=1,handled_at=CURRENT_TIMESTAMP WHERE snapshot_record_id=?", initialSnapshot);
        jdbc.update("INSERT INTO assignment_history(snapshot_record_id,previous_manager_user_id,new_manager_user_id,change_type,changed_by_user_id,reason) VALUES (?,?,?,'MANUAL_ASSIGNMENT',?,'管理员分配')",
                initialSnapshot, null, manuallyAssigned, 1L);

        long sameNameRevision = batch("ARREARS", "202609", 1);
        importRow(sameNameRevision, 2, arrears("A-OWNER", "未匹配的Excel姓名", "修正版产品"));
        confirmations.confirm(sameNameRevision, "admin");
        long stableNameSnapshot = jdbc.queryForObject("SELECT snapshot_record_id FROM excel_current_dataset WHERE identity_key='202609:A-OWNER'", Long.class);

        assertEquals(manuallyAssigned, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=?", Long.class, stableNameSnapshot));
        assertEquals("RESOLVED", jdbc.queryForObject("SELECT status FROM assignment_exception WHERE snapshot_record_id=?", String.class, stableNameSnapshot));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_history WHERE snapshot_record_id=? AND change_type='MANUAL_ASSIGNMENT'", Integer.class, stableNameSnapshot));

        long changedNameRevision = batch("ARREARS", "202609", 1);
        importRow(changedNameRevision, 2, arrears("A-OWNER", "新经理", "最新产品"));
        confirmations.confirm(changedNameRevision, "admin");
        long changedNameSnapshot = jdbc.queryForObject("SELECT snapshot_record_id FROM excel_current_dataset WHERE identity_key='202609:A-OWNER'", Long.class);

        assertEquals(rematched, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=?", Long.class, changedNameSnapshot));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_exception WHERE snapshot_record_id=?", Integer.class, changedNameSnapshot));
        assertEquals(manuallyAssigned, jdbc.queryForObject("SELECT previous_manager_user_id FROM assignment_history WHERE snapshot_record_id=? AND change_type='IMPORT_MATCH'", Long.class, changedNameSnapshot));
        assertEquals("新经理", jdbc.queryForObject("SELECT excel_manager_name FROM excel_snapshot_record WHERE id=?", String.class, changedNameSnapshot));
    }

    @Test
    void revisionSwitchesCurrentBatchAndRetainsPreviousSnapshot() {
        user("admin", "系统管理员", "ADMIN");
        user("manager", "张伟", "CUSTOMER_MANAGER");
        long original = batch("ARREARS", "202609", 1);
        importRow(original, 2, arrears("A-1", "张伟", "旧产品"));
        confirmations.confirm(original, "admin");
        long revision = batch("ARREARS", "202609", 1);
        importRow(revision, 2, arrears("A-1", "张伟", "新产品"));

        confirmations.confirm(revision, "admin");

        assertCurrent("ARREARS", "202609", revision);
        assertEquals(2, count("excel_snapshot_record"));
        assertEquals(2, count("arrears_snapshot"));
        assertEquals("旧产品", jdbc.queryForObject("SELECT carrying_product_name FROM arrears_snapshot WHERE batch_id=?", String.class, original));
        assertEquals("新产品", jdbc.queryForObject("SELECT carrying_product_name FROM arrears_snapshot WHERE batch_id=?", String.class, revision));
        assertEquals(1, count("arrears_work_order"));
        assertEquals(revision, jdbc.queryForObject("SELECT s.batch_id FROM arrears_work_order w JOIN excel_snapshot_record s ON s.id=w.current_snapshot_record_id WHERE w.statistics_key='202609' AND w.account_id='A-1'", Long.class));
        assertEquals("CONFIRMED", batchStatus(original));
        assertEquals("CONFIRMED", batchStatus(revision));
    }

    @Test
    void arrearsRevisionPreservesHandlingAndRemovedAccountReappearanceReusesOrder() {
        user("admin", "系统管理员", "ADMIN");
        long manager = user("manager", "张伟", "CUSTOMER_MANAGER");
        long original = batch("ARREARS", "202609", 2);
        importRow(original, 2, arrears("A-KEEP", "张伟", "原产品"));
        importRow(original, 3, arrears("A-REMOVE", "张伟", "待移除"));
        confirmations.confirm(original, "admin");
        long order = jdbc.queryForObject("SELECT id FROM arrears_work_order WHERE account_id='A-KEEP'", Long.class);
        long removedOrder = jdbc.queryForObject("SELECT id FROM arrears_work_order WHERE account_id='A-REMOVE'", Long.class);
        jdbc.update("UPDATE arrears_work_order SET status='跟进中',last_handled_by_user_id=?,last_handled_at=CURRENT_TIMESTAMP WHERE id=?", manager, order);
        jdbc.update("INSERT INTO arrears_work_order_status_history(work_order_id,previous_status,new_status,changed_by_user_id) VALUES (?, '待处理','跟进中',?)", order, manager);
        jdbc.update("INSERT INTO arrears_work_order_follow_up(work_order_id,note,created_by_user_id) VALUES (?, '已电话沟通', ?)", order, manager);
        java.sql.Timestamp handledAt = jdbc.queryForObject("SELECT last_handled_at FROM arrears_work_order WHERE id=?", java.sql.Timestamp.class, order);

        long revision = batch("ARREARS", "202609", 2);
        importRow(revision, 2, arrears("A-KEEP", "张伟", "修正版产品"));
        importRow(revision, 3, arrears("A-NEW", "张伟", "新增账户"));
        confirmations.confirm(revision, "admin");

        assertEquals(3, count("arrears_work_order"));
        assertEquals(original, jdbc.queryForObject("SELECT batch_id FROM excel_current_dataset WHERE identity_key='202609:A-REMOVE'", Long.class));
        assertEquals(3, jdbc.queryForObject("""
                SELECT r.source_row_number FROM excel_current_dataset d
                JOIN excel_snapshot_record s ON s.id=d.snapshot_record_id
                JOIN excel_import_row r ON r.id=s.import_row_id
                WHERE d.identity_key='202609:A-REMOVE'
                """, Integer.class));
        assertEquals(revision, jdbc.queryForObject("SELECT batch_id FROM excel_current_dataset WHERE identity_key='202609:A-KEEP'", Long.class));
        assertEquals(2, jdbc.queryForObject("""
                SELECT r.source_row_number FROM excel_current_dataset d
                JOIN excel_snapshot_record s ON s.id=d.snapshot_record_id
                JOIN excel_import_row r ON r.id=s.import_row_id
                WHERE d.identity_key='202609:A-KEEP'
                """, Integer.class));
        assertEquals("跟进中", jdbc.queryForObject("SELECT status FROM arrears_work_order WHERE id=?", String.class, order));
        assertEquals(manager, jdbc.queryForObject("SELECT last_handled_by_user_id FROM arrears_work_order WHERE id=?", Long.class, order));
        assertEquals(handledAt, jdbc.queryForObject("SELECT last_handled_at FROM arrears_work_order WHERE id=?", java.sql.Timestamp.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arrears_work_order_status_history WHERE work_order_id=?", Integer.class, order));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arrears_work_order_follow_up WHERE work_order_id=?", Integer.class, order));
        assertEquals("待处理", jdbc.queryForObject("SELECT status FROM arrears_work_order WHERE account_id='A-NEW'", String.class));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_current_dataset d
                JOIN arrears_snapshot a ON a.snapshot_record_id=d.snapshot_record_id
                WHERE d.source_type='ARREARS' AND d.statistics_key='202609' AND a.account_id='A-REMOVE'
                """, Integer.class));

        long secondRevision = batch("ARREARS", "202609", 3);
        importRow(secondRevision, 2, arrears("A-KEEP", "张伟", "第二次修正"));
        importRow(secondRevision, 3, arrears("A-NEW", "张伟", "仍存在"));
        importRow(secondRevision, 4, arrears("A-REMOVE", "张伟", "重新出现"));
        confirmations.confirm(secondRevision, "admin");
        assertEquals(removedOrder, jdbc.queryForObject("SELECT id FROM arrears_work_order WHERE account_id='A-REMOVE'", Long.class));
        assertEquals(secondRevision, jdbc.queryForObject("SELECT s.batch_id FROM arrears_work_order w JOIN excel_snapshot_record s ON s.id=w.current_snapshot_record_id WHERE w.id=?", Long.class, removedOrder));
    }

    @Test
    void sameAccountInDifferentMonthsGetsIndependentArrearsWorkOrders() {
        user("admin", "系统管理员", "ADMIN");
        user("manager", "张伟", "CUSTOMER_MANAGER");
        long september = batch("ARREARS", "202609", 1);
        importRow(september, 2, arrears("A-1", "张伟", "9月产品"));
        confirmations.confirm(september, "admin");
        long october = batch("ARREARS", "202610", 1);
        importRow(october, 2, arrears("A-1", "张伟", "10月产品"));
        confirmations.confirm(october, "admin");
        assertEquals(2, count("arrears_work_order"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(DISTINCT statistics_key) FROM arrears_work_order WHERE account_id='A-1'", Integer.class));
    }

    @Test
    void failedSnapshotWriteRollsBackAllBusinessRowsThenMarksBatchFailed() {
        user("admin", "系统管理员", "ADMIN");
        user("manager", "张伟", "CUSTOMER_MANAGER");
        long batch = batch("ARREARS", "202609", 2);
        importRow(batch, 2, arrears("A-1", "张伟", "产品甲"));
        Map<String, Object> invalid = arrears("", "张伟", "产品乙");
        importRow(batch, 3, invalid);

        var error = assertThrows(LedgerBatchConfirmationService.ConfirmationWriteException.class,
                () -> confirmations.confirm(batch, "admin"));

        assertEquals(batch, error.batchId());
        assertEquals(0, count("excel_snapshot_record"));
        assertEquals(0, count("arrears_snapshot"));
        assertEquals(0, count("arrears_work_order"));
        assertEquals(0, count("assignment_exception"));
        assertEquals(0, count("assignment_history"));
        assertEquals(0, count("excel_current_version"));
        assertEquals(0, count("excel_dataset_version"));
        assertEquals(0, count("excel_effective_row"));
        assertEquals(0, count("excel_dataset_version_row"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_dataset_period WHERE current_version_id IS NOT NULL", Integer.class));
        assertEquals("FAILED", batchStatus(batch));
        String summary = jdbc.queryForObject("SELECT validation_summary FROM excel_import_batch WHERE id=?", String.class, batch);
        assertTrue(summary.contains("快照规范化字段缺失"));
    }

    @Test
    void blockedAndDuplicateBatchesCannotBeConfirmed() {
        user("admin", "系统管理员", "ADMIN");
        long blocked = batch("ARREARS", "202609", 0, "BLOCKED");
        long duplicate = batch("ARREARS", "202609", 0, "DUPLICATE");
        long failed = batch("ARREARS", "202609", 0, "FAILED");

        assertThrows(IllegalArgumentException.class, () -> confirmations.confirm(blocked, "admin"));
        assertThrows(IllegalArgumentException.class, () -> confirmations.confirm(duplicate, "admin"));
        assertThrows(IllegalArgumentException.class, () -> confirmations.confirm(failed, "admin"));
        assertEquals(0, count("excel_snapshot_record"));
        assertEquals("BLOCKED", batchStatus(blocked));
        assertEquals("DUPLICATE", batchStatus(duplicate));
        assertEquals("FAILED", batchStatus(failed));
    }

    private long user(String username, String realName, String role) {
        jdbc.update("INSERT INTO app_user(username,real_name,role,enabled) VALUES (?,?,?,TRUE)", username, realName, role);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private long batch(String source, String date, int rows) {
        return batch(source, date, rows, "PRECHECKED");
    }

    private long batch(String source, String date, int rows, String status) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO excel_import_batch(source_type,statistics_key,batch_status,total_rows,validation_summary) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, source);
            statement.setString(2, date);
            statement.setString(3, status);
            statement.setInt(4, rows);
            statement.setString(5, "{}");
            return statement;
        }, key);
        long batchId = key.getKey().longValue();
        List<Long> versions = jdbc.queryForList("""
                SELECT current_version_id FROM excel_dataset_period
                WHERE source_type=? AND statistics_key=?
                """, Long.class, source, date);
        if (!versions.isEmpty()) {
            jdbc.update("UPDATE excel_import_batch SET preview_base_version_id=? WHERE id=?",
                    versions.get(0), batchId);
        }
        return batchId;
    }

    private void importRow(long batch, int row, Map<String, Object> fields) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String normalized = mapper.writeValueAsString(fields);
            String source = jdbc.queryForObject("SELECT source_type FROM excel_import_batch WHERE id=?", String.class, batch);
            String statisticsKey = jdbc.queryForObject("SELECT statistics_key FROM excel_import_batch WHERE id=?", String.class, batch);
            String identityHeader = "ARREARS".equals(source) ? "账户ID" : "成员服务号码";
            String identityKey = statisticsKey + ":" + fields.get(identityHeader);
            List<Map<String, Object>> rawFields = new java.util.ArrayList<>();
            int columnIndex = 1;
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("columnIndex", columnIndex++);
                raw.put("header", field.getKey());
                raw.put("cellType", field.getValue() == null ? "BLANK"
                        : field.getValue() instanceof Number ? "NUMERIC" : "STRING");
                raw.put("value", field.getValue() == null ? null : field.getValue().toString());
                raw.put("formula", null);
                rawFields.add(raw);
            }
            String rawJson = mapper.writeValueAsString(rawFields);
            jdbc.update("""
                    INSERT INTO excel_import_row(batch_id,sheet_name,source_row_number,identity_key,validation_status,
                        normalized_fields,raw_fields,warnings)
                    VALUES (?, 'Sheet1', ?, ?, 'VALID', ?, ?, '[]')
                    """, batch, row, identityKey, normalized, rawJson);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, Object> arrears(String account, String manager, String product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("日期", "202609");
        fields.put("集团编码", "G-1");
        fields.put("集团名称", "集团一");
        fields.put("集团客户经理姓名", manager);
        fields.put("账户ID", account);
        fields.put("期末欠费金额", 12.34);
        fields.put("承载产品名称", product);
        fields.put("账龄", null);
        return fields;
    }

    private Map<String, Object> lineExpiry(String serviceNumber, String product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("统计日期", "20260901");
        fields.put("集团编码", "G-1");
        fields.put("集团名称", "集团一");
        fields.put("成员服务号码", serviceNumber);
        fields.put("客户经理姓名", "李明");
        fields.put("计费截止日期", "2026-10-31");
        fields.put("产品名称", product);
        return fields;
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String batchStatus(long batch) { return jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, batch); }
    private void assertCurrent(String source, String date, long batch) {
        assertEquals(batch, jdbc.queryForObject("SELECT batch_id FROM excel_current_version WHERE source_type=? AND statistics_key=?", Long.class, source, date));
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            JdbcDataSource source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:ledger-confirmation;MODE=MySQL;DB_CLOSE_DELAY=-1");
            source.setUser("sa");
            source.setPassword("");
            return source;
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean LedgerBatchFailureRecorder failureRecorder(JdbcTemplate jdbc, ObjectMapper mapper) {
            return new LedgerBatchFailureRecorder(jdbc, mapper);
        }
        @Bean LedgerBatchConfirmationService confirmationService(JdbcTemplate jdbc, ObjectMapper mapper,
                                                                 LedgerBatchFailureRecorder recorder) {
            return new LedgerBatchConfirmationService(jdbc, mapper, recorder);
        }
    }
}
