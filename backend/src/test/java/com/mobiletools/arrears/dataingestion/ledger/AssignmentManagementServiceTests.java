package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentManagementServiceTests {

    private JdbcTemplate jdbc;
    private AssignmentManagementService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:assignment-management;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100), role VARCHAR(30), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), original_filename VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT PRIMARY KEY, batch_id BIGINT, sheet_name VARCHAR(100), source_row_number INT, identity_key VARCHAR(255), raw_fields CLOB, normalized_fields CLOB, errors CLOB, warnings CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), import_row_id BIGINT, effective_manager_user_id BIGINT, excel_manager_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT, exception_type VARCHAR(30), reason VARCHAR(1000), status VARCHAR(20), resolution VARCHAR(1000), handled_by_user_id BIGINT, handled_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE assignment_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT, previous_manager_user_id BIGINT, new_manager_user_id BIGINT, change_type VARCHAR(30), changed_by_user_id BIGINT, reason VARCHAR(1000), changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT, operation_type VARCHAR(60), target_type VARCHAR(60), target_id BIGINT, result VARCHAR(30), detail VARCHAR(2000))");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO app_user(id,username,real_name,role,enabled) VALUES (1,'admin','管理员','ADMIN',TRUE),(2,'manager-a','经理甲','CUSTOMER_MANAGER',TRUE),(3,'manager-b','经理乙','CUSTOMER_MANAGER',TRUE),(4,'disabled','停用','CUSTOMER_MANAGER',FALSE)");
        jdbc.update("INSERT INTO excel_import_batch(id,source_type,statistics_key,original_filename) VALUES (100,'ARREARS','202609','arrears.xlsx'),(101,'ARREARS','202608','arrears-previous.xlsx'),(200,'LINE_EXPIRY','20260901','line.xlsx')");
        jdbc.update("INSERT INTO excel_import_row(id,batch_id,sheet_name,source_row_number,identity_key,raw_fields,normalized_fields,errors,warnings) VALUES (1000,100,'欠费表',2,'A-1','[]','{}','[]','[]'),(1001,100,'欠费表',3,'A-2','[]','{}','[]','[]'),(1002,101,'欠费表',2,'A-1','[]','{}','[]','[]'),(2000,200,'专线表',2,'S-1','[]','{}','[]','[]')");
        jdbc.update("INSERT INTO excel_snapshot_record(id,batch_id,source_type,import_row_id,effective_manager_user_id,excel_manager_name) VALUES (10,100,'ARREARS',1000,NULL,'Excel甲'),(11,100,'ARREARS',1001,NULL,'Excel乙'),(12,101,'ARREARS',1002,NULL,'Excel甲'),(20,200,'LINE_EXPIRY',2000,NULL,'Excel专线')");
        jdbc.update("INSERT INTO arrears_snapshot(snapshot_record_id,group_code,group_name) VALUES (10,'G-1','甲集团'),(11,'G-1','甲集团'),(12,'G-1','甲集团')");
        jdbc.update("INSERT INTO line_expiry_snapshot(snapshot_record_id,group_code,group_name) VALUES (20,'G-1','甲集团')");
        jdbc.update("INSERT INTO assignment_exception(snapshot_record_id,exception_type,reason,status) VALUES (10,'NO_MATCH','未匹配','OPEN'),(11,'NO_MATCH','未匹配','OPEN'),(12,'NO_MATCH','未匹配','OPEN'),(20,'NO_MATCH','未匹配','OPEN')");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202609", 1000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 10);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 11);
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202608", 900);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 900, "ARREARS", "202608", 12);
        ComposedLedgerTestFixtures.addPeriod(jdbc, "LINE_EXPIRY", "20260901", 2000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 2000, "LINE_EXPIRY", "20260901", 20);
        service = new AssignmentManagementService(jdbc,
                new AssignmentExceptionQueryService(jdbc, new ObjectMapper()));
    }

    @Test
    void bulkAssignmentUpdatesOnlySelectedRowsAndResolvesExceptions() {
        var result = service.assign("ARREARS", List.of(10L, 11L), 2L, 1L, "管理员批量分配");
        assertEquals(2, result.selectedCount());
        assertEquals(List.of(2L, 2L), jdbc.queryForList("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id IN (10,11) ORDER BY id", Long.class));
        assertEquals(List.of("RESOLVED", "RESOLVED"), jdbc.queryForList("SELECT status FROM assignment_exception WHERE snapshot_record_id IN (10,11) ORDER BY snapshot_record_id", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_history WHERE change_type='MANUAL_ASSIGNMENT'", Integer.class));
        assertEquals("Excel甲", jdbc.queryForObject("SELECT excel_manager_name FROM excel_snapshot_record WHERE id=10", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_snapshot_record WHERE id=20 AND effective_manager_user_id=2", Integer.class));
    }

    @Test
    void mixedSourcesAndDisabledTargetAreRejectedBeforeMutation() {
        assertThrows(IllegalArgumentException.class, () -> service.assign("ARREARS", List.of(10L, 20L), 2L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> service.assign("ARREARS", List.of(10L), 4L, 1L, null));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=10", Long.class));
    }

    @Test
    void reassignmentCreatesHistoryAndChangesOnlyTheSelectedRow() {
        service.assign("ARREARS", List.of(10L), 2L, 1L, null);
        service.assign("ARREARS", List.of(10L), 3L, 1L, "改派");
        assertEquals(3L, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=10", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM assignment_history WHERE snapshot_record_id=10", Integer.class));
        assertEquals(2L, jdbc.queryForObject("SELECT previous_manager_user_id FROM assignment_history WHERE snapshot_record_id=10 AND change_type='REASSIGNMENT'", Long.class));
        assertEquals(3L, jdbc.queryForObject("SELECT new_manager_user_id FROM assignment_history WHERE snapshot_record_id=10 AND change_type='REASSIGNMENT'", Long.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=11", Long.class));
    }

    @Test
    void historyDetailShowsEachActorAndReasonWhileOtherDatesAndSourcesStayIsolated() {
        service.assign("ARREARS", List.of(10L), 2L, 1L, "首次分配");
        service.assign("ARREARS", List.of(10L), 3L, 1L, "改派原因");

        var exceptionId = jdbc.queryForObject("SELECT id FROM assignment_exception WHERE snapshot_record_id=10", Long.class);
        var detail = new AssignmentExceptionQueryService(jdbc, new ObjectMapper()).detail(exceptionId);
        assertEquals(2, detail.assignmentHistory().size());
        assertEquals("MANUAL_ASSIGNMENT", detail.assignmentHistory().get(0).changeType());
        assertEquals("REASSIGNMENT", detail.assignmentHistory().get(1).changeType());
        assertEquals("首次分配", detail.assignmentHistory().get(0).reason());
        assertEquals("改派原因", detail.assignmentHistory().get(1).reason());
        assertEquals(3L, detail.assignmentHistory().get(1).newManagerUserId());
        assertEquals("经理乙", detail.assignmentHistory().get(1).newManagerName());
        assertNotNull(detail.assignmentHistory().get(1).changedAt());

        assertEquals(3L, jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=10", Long.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=11", Long.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=12", Long.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=20", Long.class));
        assertEquals("Excel甲", jdbc.queryForObject("SELECT excel_manager_name FROM excel_snapshot_record WHERE id=10", String.class));
    }

    @Test
    void filteredSelectionIncludesRowsAcrossPagesButExcludesOtherSources() {
        var result = service.assignFiltered("ARREARS", "202609", "G-1", null, null,
                null, "OPEN", 2L, 1L, null);
        assertEquals(2, result.selectedCount());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM excel_snapshot_record WHERE id IN (10,11) AND effective_manager_user_id=2", Integer.class));
        assertNull(jdbc.queryForObject("SELECT effective_manager_user_id FROM excel_snapshot_record WHERE id=20", Long.class));
    }
}
