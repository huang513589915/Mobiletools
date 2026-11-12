package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class ArrearsWorkOrderServiceTests {
    private JdbcTemplate jdbc;
    private ArrearsWorkOrderService service;
    private SnapshotAccessScopeService.Scope admin;
    private SnapshotAccessScopeService.Scope managerA;
    private SnapshotAccessScopeService.Scope managerB;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:arrears-work-orders;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE excel_current_version(source_type VARCHAR(20), statistics_key VARCHAR(8), batch_id BIGINT, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_snapshot_record(id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_snapshot(snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT, account_id VARCHAR(100))");
        jdbc.execute("CREATE TABLE arrears_work_order(id BIGINT PRIMARY KEY, statistics_key VARCHAR(6), account_id VARCHAR(100), current_snapshot_record_id BIGINT, status VARCHAR(20), last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE arrears_work_order_status_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, previous_status VARCHAR(20), new_status VARCHAR(20), changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE arrears_work_order_follow_up(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, note CLOB, created_by_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','管理员'),(2,'manager-a','经理甲'),(3,'manager-b','经理乙')");
        jdbc.update("INSERT INTO excel_current_version VALUES ('ARREARS','202609',100)");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (10,100,'ARREARS',2),(11,100,'ARREARS',3),(12,100,'ARREARS',NULL)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES (10,100,'A-1'),(11,100,'A-2'),(12,100,'A-3')");
        jdbc.update("INSERT INTO arrears_work_order VALUES (1,'202609','A-1',10,'待处理',NULL,NULL),(2,'202609','A-2',11,'待处理',NULL,NULL),(3,'202609','A-3',12,'待处理',NULL,NULL)");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202609", 1000);
        for (long id = 10; id <= 12; id++) {
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", id,
                    100, "202609:A-" + (id - 9), "[]", "{}");
        }
        service = new ArrearsWorkOrderService(jdbc);
        admin = new SnapshotAccessScopeService.Scope(true, 1);
        managerA = new SnapshotAccessScopeService.Scope(false, 2);
        managerB = new SnapshotAccessScopeService.Scope(false, 3);
    }

    @Test
    void recordsStatusChangesAndAppendsNotesWithoutOverwriting() {
        var started = service.updateHandling(1, "跟进中", "  已联系客户  ", managerA);
        assertEquals("跟进中", started.status());
        assertTrue(started.statusChanged());
        assertTrue(started.followUpAdded());
        assertEquals(1, count("arrears_work_order_status_history"));
        assertEquals("已联系客户", jdbc.queryForObject("SELECT note FROM arrears_work_order_follow_up", String.class));

        var noteOnly = service.updateHandling(1, "跟进中", "客户承诺本周处理", managerA);
        assertFalse(noteOnly.statusChanged());
        assertTrue(noteOnly.followUpAdded());
        assertEquals(1, count("arrears_work_order_status_history"));
        assertEquals(2, count("arrears_work_order_follow_up"));
        assertNotNull(noteOnly.lastHandledAt());

        service.updateHandling(1, "已结清", null, managerA);
        service.updateHandling(1, "待处理", null, managerA);
        assertEquals(3, count("arrears_work_order_status_history"));
        assertEquals(2, count("arrears_work_order_follow_up"));
    }

    @Test
    void blankNoteAndUnchangedStatusAreNoOpsAndNotesRequireFollowUpStatus() {
        var empty = service.updateHandling(1, "待处理", "   ", managerA);
        assertFalse(empty.changed());
        assertNull(empty.lastHandledAt());
        assertEquals(0, count("arrears_work_order_status_history"));
        assertEquals(0, count("arrears_work_order_follow_up"));
        assertThrows(IllegalArgumentException.class, () -> service.updateHandling(1, "已结清", "不能写在结清状态", managerA));
        assertThrows(IllegalArgumentException.class, () -> service.updateHandling(1, "无效", null, managerA));
    }

    @Test
    void enforcesCurrentAssignmentAndUnassignedAdminOnlyAccess() {
        assertThrows(AccessDeniedException.class, () -> service.updateHandling(2, "跟进中", null, managerA));
        assertThrows(AccessDeniedException.class, () -> service.updateHandling(3, "跟进中", null, managerA));
        assertEquals(0, count("arrears_work_order_status_history"));
        assertEquals("已结清", service.updateHandling(3, "已结清", null, admin).status());
    }

    @Test
    void timelineMergesStatusAndFollowUpEventsInStableOrder() {
        service.updateHandling(1, "跟进中", "先联系", managerA);
        service.updateHandling(1, "跟进中", "继续沟通", managerA);
        var timeline = service.timeline(1);
        assertEquals(3, timeline.size());
        assertEquals("STATUS", timeline.get(0).type());
        assertEquals("FOLLOW_UP", timeline.get(1).type());
        assertEquals("先联系", timeline.get(1).note());
        assertEquals("经理甲", timeline.get(0).actorName());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
