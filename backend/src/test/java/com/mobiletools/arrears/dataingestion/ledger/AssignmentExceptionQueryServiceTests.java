package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssignmentExceptionQueryServiceTests {

    private JdbcTemplate jdbc;
    private AssignmentExceptionQueryService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:assignment-exceptions;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100))");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), original_filename VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT, sheet_name VARCHAR(100), source_row_number INT, identity_key VARCHAR(255), raw_fields CLOB, normalized_fields CLOB, errors CLOB, warnings CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), import_row_id BIGINT, excel_manager_name VARCHAR(255), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, group_code VARCHAR(100), group_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT, exception_type VARCHAR(30), reason VARCHAR(1000), status VARCHAR(20), resolution VARCHAR(1000), handled_by_user_id BIGINT, handled_at TIMESTAMP, created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE assignment_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT, previous_manager_user_id BIGINT, new_manager_user_id BIGINT, change_type VARCHAR(30), changed_by_user_id BIGINT, reason VARCHAR(1000), changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO app_user(id,username,real_name) VALUES (1,'manager','张伟')");
        jdbc.update("INSERT INTO excel_import_batch(id,source_type,statistics_key,original_filename) VALUES (10,'ARREARS','202609','arrears.xlsx'),(20,'LINE_EXPIRY','20260901','line.xlsx')");
        jdbc.update("INSERT INTO excel_import_row(id,batch_id,sheet_name,source_row_number,identity_key,raw_fields,normalized_fields,errors,warnings) VALUES "
                + "(100,10,'欠费表',8,'202609:A-1','[{\"header\":\"账户ID\",\"value\":\"A-1\"}]','{\"账户ID\":\"A-1\"}','[]','[]'),"
                + "(101,10,'欠费表',9,'202609:A-2','[{\"header\":\"账户ID\",\"value\":\"A-2\"}]','{\"账户ID\":\"A-2\"}','[]','[]'),"
                + "(200,20,'专线表',9,'20260901:S-1','[{\"header\":\"成员服务号码\",\"value\":\"S-1\"}]','{\"成员服务号码\":\"S-1\"}','[]','[]')");
        jdbc.update("INSERT INTO excel_snapshot_record(id,batch_id,source_type,import_row_id,excel_manager_name,effective_manager_user_id) VALUES "
                + "(1000,10,'ARREARS',100,'不存在经理',NULL),(1001,10,'ARREARS',101,'不存在经理',NULL),(2000,20,'LINE_EXPIRY',200,'张伟',1)");
        jdbc.update("INSERT INTO arrears_snapshot(snapshot_record_id,group_code,group_name) VALUES (1000,'G-1','甲集团'),(1001,'G-1','甲集团')");
        jdbc.update("INSERT INTO line_expiry_snapshot(snapshot_record_id,group_code,group_name) VALUES (2000,'G-2','乙集团')");
        jdbc.update("INSERT INTO assignment_exception(id,snapshot_record_id,exception_type,reason,status,created_at) VALUES "
                + "(500,1000,'NO_MATCH','未找到匹配账号','OPEN',CURRENT_TIMESTAMP),(501,1001,'NO_MATCH','未找到匹配账号','OPEN',CURRENT_TIMESTAMP),"
                + "(600,2000,'MULTIPLE_MATCHES','重名','RESOLVED',CURRENT_TIMESTAMP)");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202609", 1000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 1000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 1001);
        ComposedLedgerTestFixtures.addPeriod(jdbc, "LINE_EXPIRY", "20260901", 2000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 2000, "LINE_EXPIRY", "20260901", 2000);
        service = new AssignmentExceptionQueryService(jdbc, new ObjectMapper());
    }

    @Test
    void listsEachSourceRowWithTraceabilityAndAppliesAllFilters() {
        var page = service.list("ARREARS", "202609", "G-1", "甲", "不存在", 10L, "OPEN", 1, 20);
        assertEquals(2, page.total());
        assertEquals(2, page.items().size());
        assertNotEquals(page.items().get(0).identityKey(), page.items().get(1).identityKey());
        var item = page.items().get(0);
        assertEquals("ARREARS", item.sourceType());
        assertEquals("欠费表", item.sheetName());
        assertTrue(page.items().stream().map(AssignmentExceptionQueryService.ExceptionItem::sourceRowNumber)
                .toList().containsAll(List.of(8, 9)));
        assertEquals("未找到匹配账号", item.reason());

        assertEquals(0, service.list("ARREARS", "202609", "G-2", null, null, null, null, 1, 20).total());
        assertEquals(1, service.list("LINE_EXPIRY", null, null, null, null, null, "RESOLVED", 1, 20).total());
    }

    @Test
    void detailIncludesRawFieldsAndDoesNotMergeDifferentRows() {
        var detail = service.detail(500);
        assertEquals("不存在经理", detail.item().excelManagerName());
        assertEquals("A-1", detail.rawFields().get(0).get("value").asText());
        assertEquals("A-1", detail.normalizedFields().get("账户ID").asText());
        assertEquals("甲集团", detail.item().groupName());
        assertThrows(IllegalArgumentException.class, () -> service.detail(999));
    }

    @Test
    void validatesSourceDateStatusAndPageSize() {
        assertThrows(IllegalArgumentException.class, () -> service.list("ARREARS", "20260901", null, null, null, null, null, 1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, null, null, null, "UNKNOWN", 1, 20));
        assertThrows(IllegalArgumentException.class, () -> service.list(null, null, null, null, null, null, null, 1, 10));
    }
}
