package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotAccessScopeServiceTests {

    private JdbcTemplate jdbc;
    private SnapshotAccessScopeService scopes;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:snapshot-access;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT PRIMARY KEY, username VARCHAR(100), role VARCHAR(30), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8))");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, account_id VARCHAR(30))");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, member_service_number VARCHAR(30))");
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','ADMIN',TRUE),(2,'manager-a','CUSTOMER_MANAGER',TRUE),(3,'manager-b','CUSTOMER_MANAGER',TRUE),(4,'disabled','CUSTOMER_MANAGER',FALSE)");
        jdbc.update("INSERT INTO excel_import_batch VALUES (100,'ARREARS','202609'),(90,'ARREARS','202608'),(200,'LINE_EXPIRY','20260930')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES "
                + "(10,100,'ARREARS',2),(11,100,'ARREARS',3),(12,100,'ARREARS',NULL),"
                + "(9,90,'ARREARS',2),(20,200,'LINE_EXPIRY',3),(21,200,'LINE_EXPIRY',2),(22,200,'LINE_EXPIRY',NULL)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES (10,'A-1'),(11,'A-2'),(12,'A-3'),(9,'A-1')");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES (20,'S-1'),(21,'S-2'),(22,'S-3')");
        scopes = new SnapshotAccessScopeService(jdbc);
    }

    @Test
    void administratorSeesEveryRowIncludingUnassignedAndManagerOnlySeesOwnRows() {
        assertEquals(List.of(10L, 11L, 12L), queryIds(scope("admin", "ROLE_ADMIN"), "ARREARS", "202609"));
        assertEquals(List.of(10L), queryIds(scope("manager-a", "ROLE_CUSTOMER_MANAGER"), "ARREARS", "202609"));
        assertEquals(List.of(11L), queryIds(scope("manager-b", "ROLE_CUSTOMER_MANAGER"), "ARREARS", "202609"));
        assertFalse(queryIds(scope("manager-a", "ROLE_CUSTOMER_MANAGER"), "ARREARS", "202609").contains(12L));
    }

    @Test
    void historicalOwnershipAndReassignmentAreSnapshotScopedAndTakeEffectImmediately() {
        var managerA = scope("manager-a", "ROLE_CUSTOMER_MANAGER");
        var managerB = scope("manager-b", "ROLE_CUSTOMER_MANAGER");
        assertEquals(List.of(9L, 10L), allArrearsIds(managerA));

        jdbc.update("UPDATE excel_snapshot_record SET effective_manager_user_id=3 WHERE id=10");

        assertEquals(List.of(9L), allArrearsIds(managerA));
        assertEquals(List.of(10L, 11L), allArrearsIds(managerB));
        assertEquals(List.of(9L), queryIds(managerA, "ARREARS", "202608"));
    }

    @Test
    void sourceVisibilityIsIndependentAndUnassignedRowsStayAdminOnly() {
        var managerA = scope("manager-a", "ROLE_CUSTOMER_MANAGER");
        var managerB = scope("manager-b", "ROLE_CUSTOMER_MANAGER");

        assertEquals(List.of(10L), queryIds(managerA, "ARREARS", "202609"));
        assertEquals(List.of(21L), queryIds(managerA, "LINE_EXPIRY", "20260930"));
        assertEquals(List.of(11L), queryIds(managerB, "ARREARS", "202609"));
        assertEquals(List.of(20L), queryIds(managerB, "LINE_EXPIRY", "20260930"));
        assertEquals(List.of(10L, 11L, 12L), queryIds(scope("admin", "ROLE_ADMIN"), "ARREARS", "202609"));
        assertEquals(List.of(20L, 21L, 22L), queryIds(scope("admin", "ROLE_ADMIN"), "LINE_EXPIRY", "20260930"));
    }

    @Test
    void rejectsDisabledAccountsAndAuthorityRoleMismatch() {
        assertThrows(AccessDeniedException.class, () -> scope("disabled", "ROLE_CUSTOMER_MANAGER"));
        assertThrows(AccessDeniedException.class, () -> scope("manager-a", "ROLE_ADMIN"));
    }

    @Test
    void rejectsUnsafeSqlAlias() {
        var scope = scope("manager-a", "ROLE_CUSTOMER_MANAGER");
        assertThrows(IllegalArgumentException.class,
                () -> scope.appendPredicate("s; DROP TABLE app_user", new StringBuilder(" WHERE 1=1"), new ArrayList<>()));
    }

    private List<Long> allArrearsIds(SnapshotAccessScopeService.Scope scope) {
        return queryIds(scope, "ARREARS", null);
    }

    private List<Long> queryIds(SnapshotAccessScopeService.Scope scope, String sourceType, String statisticsKey) {
        StringBuilder where = new StringBuilder(" WHERE b.source_type=?");
        List<Object> parameters = new ArrayList<>(List.of(sourceType));
        if (statisticsKey != null) {
            where.append(" AND b.statistics_key=?");
            parameters.add(statisticsKey);
        }
        scope.appendPredicate("s", where, parameters);
        return jdbc.queryForList("SELECT s.id FROM excel_snapshot_record s "
                + "JOIN excel_import_batch b ON b.id=s.batch_id AND b.source_type=s.source_type "
                + where + " ORDER BY s.id", Long.class, parameters.toArray());
    }

    private SnapshotAccessScopeService.Scope scope(String username, String authority) {
        var authentication = new UsernamePasswordAuthenticationToken(username, "n/a",
                List.of(new SimpleGrantedAuthority(authority)));
        return scopes.resolve(authentication);
    }
}
