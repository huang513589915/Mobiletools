package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.*;

class LineRenewalWorkOrderServiceTests {
    private JdbcTemplate jdbc;
    private LineRenewalWorkOrderService service;
    private SnapshotAccessScopeService.Scope admin;
    private SnapshotAccessScopeService.Scope managerA;
    private SnapshotAccessScopeService.Scope managerB;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:line-renewal-work-orders;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE excel_current_version(source_type VARCHAR(20), statistics_key VARCHAR(8), batch_id BIGINT, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_snapshot_record(id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE line_expiry_snapshot(snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT, member_service_number VARCHAR(100))");
        jdbc.execute("CREATE TABLE line_renewal_work_order(id BIGINT PRIMARY KEY, statistics_key VARCHAR(8), member_service_number VARCHAR(100), current_snapshot_record_id BIGINT, status VARCHAR(20), last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE line_renewal_status_history(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, previous_status VARCHAR(20), new_status VARCHAR(20), changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE line_renewal_work_order_follow_up(id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, note CLOB NOT NULL, created_by_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','系统管理员'),(2,'manager-a','经理甲'),(3,'manager-b','经理乙')");
        jdbc.update("INSERT INTO excel_current_version VALUES ('LINE_EXPIRY','20261001',100)");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (10,100,'LINE_EXPIRY',2),(11,100,'LINE_EXPIRY',3),(12,100,'LINE_EXPIRY',NULL),(9,90,'LINE_EXPIRY',2)");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES (10,100,'S-1'),(11,100,'S-2'),(12,100,'S-3'),(9,90,'S-1')");
        jdbc.update("INSERT INTO line_renewal_work_order VALUES (1,'20261001','S-1',10,'待处理',NULL,NULL),(2,'20261001','S-2',11,'待处理',NULL,NULL),(3,'20261001','S-3',12,'待处理',NULL,NULL),(4,'20260901','S-1',9,'待处理',NULL,NULL)");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "LINE_EXPIRY", "20261001", 1000);
        for (long id = 10; id <= 12; id++) {
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", id,
                    100, "20261001:S-" + (id - 9), "[]", "{}");
        }
        service = new LineRenewalWorkOrderService(jdbc);
        admin = new SnapshotAccessScopeService.Scope(true, 1);
        managerA = new SnapshotAccessScopeService.Scope(false, 2);
        managerB = new SnapshotAccessScopeService.Scope(false, 3);
    }

    @Test
    void managerCanMoveBetweenAnyStatusesAndHistoryTracksEachRealChange() {
        var started = service.updateStatus(1, "跟进中", managerA);
        assertTrue(started.changed());
        assertEquals("跟进中", started.status());
        assertEquals(2L, started.lastHandledByUserId());
        assertEquals("经理甲", started.lastHandledByName());
        assertNotNull(started.lastHandledAt());

        var completed = service.updateStatus(1, "已续签", managerA);
        var reopened = service.updateStatus(1, "不续签", managerA);
        var reversed = service.updateStatus(1, "跟进中", managerA);
        assertEquals("跟进中", reversed.status());
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_status_history WHERE work_order_id=1", Integer.class));
        assertEquals("已续签", jdbc.queryForObject("SELECT new_status FROM line_renewal_status_history WHERE id=2", String.class));
        assertEquals("不续签", jdbc.queryForObject("SELECT new_status FROM line_renewal_status_history WHERE id=3", String.class));
        assertEquals("跟进中", jdbc.queryForObject("SELECT new_status FROM line_renewal_status_history WHERE id=4", String.class));
    }

    @Test
    void noOpDoesNotChangeLastHandlerTimeOrAddHistory() {
        service.updateStatus(1, "跟进中", managerA);
        Long handler = jdbc.queryForObject("SELECT last_handled_by_user_id FROM line_renewal_work_order WHERE id=1", Long.class);
        java.sql.Timestamp handledAt = jdbc.queryForObject("SELECT last_handled_at FROM line_renewal_work_order WHERE id=1", java.sql.Timestamp.class);

        var noChange = service.updateStatus(1, "跟进中", managerA);

        assertFalse(noChange.changed());
        assertEquals(handler, jdbc.queryForObject("SELECT last_handled_by_user_id FROM line_renewal_work_order WHERE id=1", Long.class));
        assertEquals(handledAt, jdbc.queryForObject("SELECT last_handled_at FROM line_renewal_work_order WHERE id=1", java.sql.Timestamp.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_status_history WHERE work_order_id=1", Integer.class));
    }

    @Test
    void assignmentAndCurrentVersionAreCheckedForEveryUpdate() {
        assertThrows(AccessDeniedException.class, () -> service.updateStatus(2, "跟进中", managerA));
        assertThrows(AccessDeniedException.class, () -> service.updateStatus(3, "跟进中", managerA));
        assertThrows(AccessDeniedException.class, () -> service.updateStatus(4, "跟进中", managerA));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_status_history", Integer.class));

        var administratorUpdate = service.updateStatus(3, "已续签", admin);
        assertEquals("已续签", administratorUpdate.status());
        assertEquals(1L, administratorUpdate.lastHandledByUserId());
    }

    @Test
    void rejectsUnknownStatusAndMissingWorkOrder() {
        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(1, "处理中", managerA));
        assertThrows(AccessDeniedException.class, () -> service.updateStatus(999, "跟进中", admin));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_status_history", Integer.class));
    }

    @Test
    void followUpNotesAppendOnlyWhileFollowingAndMergeIntoTimeline() {
        var started = service.updateHandling(1, "跟进中", " 已联系客户 ", managerA);
        assertTrue(started.statusChanged());
        assertTrue(started.followUpAdded());
        assertEquals("已联系客户", jdbc.queryForObject("SELECT note FROM line_renewal_work_order_follow_up", String.class));

        var appended = service.updateHandling(1, "跟进中", "客户要求下周回访", managerA);
        assertFalse(appended.statusChanged());
        assertTrue(appended.followUpAdded());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_status_history WHERE work_order_id=1", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_work_order_follow_up WHERE work_order_id=1", Integer.class));
        assertEquals(3, service.timeline(1).size());
        assertEquals("STATUS", service.timeline(1).get(0).type());
        assertEquals("FOLLOW_UP", service.timeline(1).get(1).type());
        assertEquals("已联系客户", service.timeline(1).get(1).note());
    }

    @Test
    void blankFollowUpAndUnchangedStatusDoNotTouchHandlingFields() {
        var noOp = service.updateHandling(1, "待处理", "   ", managerA);
        assertFalse(noOp.changed());
        assertNull(noOp.lastHandledAt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM line_renewal_work_order_follow_up", Integer.class));
        assertThrows(IllegalArgumentException.class, () -> service.updateHandling(1, "已续签", "说明不能保存在该状态", managerA));
    }
}
