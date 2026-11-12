package com.mobiletools.arrears.dataingestion.ledger;

import org.h2.jdbcx.JdbcDataSource;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class ArrearsLedgerQueryServiceTests {

    private JdbcTemplate jdbc;
    private ArrearsLedgerQueryService service;
    private SnapshotAccessScopeService.Scope admin;
    private SnapshotAccessScopeService.Scope managerA;
    private SnapshotAccessScopeService.Scope managerB;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:arrears-ledger-query;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100), role VARCHAR(30), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE excel_current_version (source_type VARCHAR(20), statistics_key VARCHAR(8), batch_id BIGINT, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), original_filename VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT PRIMARY KEY, batch_id BIGINT, sheet_name VARCHAR(100), source_row_number INT, identity_key VARCHAR(255), raw_fields CLOB, normalized_fields CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), import_row_id BIGINT, excel_manager_name VARCHAR(255), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT, status VARCHAR(20))");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), account_id VARCHAR(100), group_code VARCHAR(100), group_name VARCHAR(255), carrying_product_name VARCHAR(255), ending_arrears_amount DECIMAL(20,4), aging_value VARCHAR(100))");
        jdbc.execute("CREATE TABLE arrears_work_order (id BIGINT PRIMARY KEY, statistics_key VARCHAR(6), account_id VARCHAR(100), current_snapshot_record_id BIGINT, status VARCHAR(20), last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE arrears_work_order_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, previous_status VARCHAR(20), new_status VARCHAR(20), changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE arrears_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT, note CLOB, created_by_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','管理员','ADMIN',TRUE),(2,'manager-a','经理甲','CUSTOMER_MANAGER',TRUE),(3,'manager-b','经理乙','CUSTOMER_MANAGER',TRUE),(4,'manager-c','经理丙','CUSTOMER_MANAGER',TRUE)");
        jdbc.update("INSERT INTO excel_import_batch VALUES (100,'ARREARS','202609','arrears-current.xlsx'),(99,'ARREARS','202609','arrears-revision.xlsx'),(90,'ARREARS','202608','arrears-old.xlsx'),(200,'LINE_EXPIRY','20260930','line.xlsx')");
        jdbc.update("INSERT INTO excel_current_version VALUES ('ARREARS','202609',100),('ARREARS','202608',90),('LINE_EXPIRY','20260930',200)");
        jdbc.update("INSERT INTO excel_import_row VALUES "
                + "(1000,100,'欠费表',2,'202609:A-1','[{\"columnIndex\":1,\"header\":\"日期\",\"value\":\"202609\"},{\"columnIndex\":2,\"header\":\"是否达到停机标准\",\"value\":\"是\"}]','{}'),"
                + "(1001,100,'欠费表',3,'202609:A-2','[]','{}'),(1002,100,'欠费表',4,'202609:A-3','[]','{}'),"
                + "(900,90,'欠费表',2,'202608:A-1','[]','{}'),(990,99,'欠费表',2,'202609:A-1','[]','{}')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES "
                + "(10,100,'ARREARS',1000,'Excel甲',2),(11,100,'ARREARS',1001,'Excel乙',3),(12,100,'ARREARS',1002,'Excel未分配',NULL),"
                + "(9,90,'ARREARS',900,'Excel甲',2),(8,99,'ARREARS',990,'旧版本经理',2)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES "
                + "(10,100,'ARREARS','A-1','G-1','甲集团','产品甲',12.50,'30天'),"
                + "(11,100,'ARREARS','A-2','G-1','甲集团','产品乙',0,'0天'),"
                + "(12,100,'ARREARS','A-3','G-2','乙集团','产品甲',99.99,NULL),"
                + "(9,90,'ARREARS','A-1','G-1','甲集团','旧产品',5.00,'10天'),"
                + "(8,99,'ARREARS','A-1','G-1','甲集团','已被替换的修正版',7.00,'15天')");
        jdbc.update("INSERT INTO arrears_work_order VALUES "
                + "(1,'202609','A-1',10,'跟进中',2,CURRENT_TIMESTAMP),"
                + "(2,'202609','A-2',11,'待处理',NULL,NULL),(3,'202609','A-3',12,'已结清',1,CURRENT_TIMESTAMP),"
                + "(4,'202608','A-1',9,'待处理',NULL,NULL)");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202609", 1000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 10);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 11);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 12);
        ComposedLedgerTestFixtures.addPeriod(jdbc, "ARREARS", "202608", 900);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 900, "ARREARS", "202608", 9);
        jdbc.update("INSERT INTO assignment_exception(snapshot_record_id,status) VALUES (12,'OPEN'),(10,'RESOLVED')");
        service = new ArrearsLedgerQueryService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneId.of("Asia/Shanghai")));
        admin = new SnapshotAccessScopeService.Scope(true, 1);
        managerA = new SnapshotAccessScopeService.Scope(false, 2);
        managerB = new SnapshotAccessScopeService.Scope(false, 3);
    }

    @Test
    void defaultsToLatestStatisticsKeyAndOrdersByGroupThenAccount() {
        var page = service.list(ArrearsLedgerQueryService.Filters.defaults(), admin);
        assertEquals("202609", page.statisticsKey());
        assertEquals(List.of("202609", "202608"), page.availableStatisticsKeys());
        assertEquals(3, page.total());
        assertEquals(List.of("A-1", "A-2", "A-3"), page.items().stream()
                .map(ArrearsLedgerQueryService.Item::accountId).toList());
        assertEquals("Excel甲", page.items().get(0).excelManagerName());
        assertEquals(10L, page.items().get(0).snapshotRecordId());
        assertEquals("Excel乙", page.items().get(1).excelManagerName());
        assertEquals("12.5000", page.items().get(0).endingArrearsAmount().toPlainString());
    }

    @Test
    void defaultsToBusinessMonthAcrossUtcBoundaryAndKeepsEmptyCurrentMonthEmpty() {
        var crossedBoundary = new ArrearsLedgerQueryService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneId.of("Asia/Shanghai")));

        var currentMonth = crossedBoundary.list(ArrearsLedgerQueryService.Filters.defaults(), admin);
        assertNull(currentMonth.statisticsKey());
        assertEquals(0, currentMonth.total());
        assertEquals(List.of("202609", "202608"), currentMonth.availableStatisticsKeys());

        var explicitPreviousMonth = crossedBoundary.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20, "202609", "202609"), admin);
        assertEquals("202609", explicitPreviousMonth.statisticsKey());
        assertEquals(3, explicitPreviousMonth.total());
    }

    @Test
    void appliesAllFiltersAndManagerPredicateBeforePagination() {
        var filtered = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", "G-1", null, "产品", 3L, 1, 20), admin);
        assertEquals(1, filtered.total());
        assertEquals("A-2", filtered.items().get(0).accountId());

        var accountFiltered = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, "A-1", null, null, 1, 20), admin);
        assertEquals(1, accountFiltered.total());
        assertEquals("A-1", accountFiltered.items().get(0).accountId());

        var managerPage = service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20), managerA);
        assertEquals(1, managerPage.total());
        assertEquals("A-1", managerPage.items().get(0).accountId());
        assertEquals(1, service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 20), managerB).total());
    }

    @Test
    void supportsHistoricalDateAndPageSizesWithoutCrossingCurrentVersions() {
        var historical = service.list(new ArrearsLedgerQueryService.Filters(
                "202608", null, null, null, null, 1, 20), managerA);
        assertEquals("202608", historical.statisticsKey());
        assertEquals(1, historical.total());
        assertEquals("旧产品", historical.items().get(0).carryingProductName());

        for (long id = 30; id < 51; id++) {
            jdbc.update("INSERT INTO excel_snapshot_record VALUES (?,?,'ARREARS',?,?,?)", id, 100,
                    id, "Excel经理", 2L);
            jdbc.update("INSERT INTO arrears_snapshot VALUES (?,100,'ARREARS',?,'G-9','分页集团','分页产品',1,'1天')",
                    id, "Z-" + id);
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", id,
                    100, "202609:Z-" + id, "[]", "{}");
        }
        var page = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 2, 20), admin);
        assertEquals(4, page.items().size());
        assertEquals(24, page.total());
        var fullyFilteredPage = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", "G-9", "Z-", "分页产品", 2L, 2, 20), managerA);
        assertEquals(21, fullyFilteredPage.total());
        assertEquals(1, fullyFilteredPage.items().size());
        assertEquals("Z-50", fullyFilteredPage.items().get(0).accountId());
        assertThrows(IllegalArgumentException.class, () -> service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 10), admin));
    }

    @Test
    void filtersAndExportsAcrossInclusiveStatisticsMonthRange() {
        var ranged = service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20, "202608", "202609"), admin);
        assertEquals("202609", ranged.statisticsKey());
        assertEquals(4, ranged.total());
        assertEquals(2, service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20, "202608", "202609"), managerA).total());

        assertThrows(IllegalArgumentException.class, () -> service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20, "202610", "202609"), admin));
    }

    @Test
    void filtersWorkOrdersByStatusAcrossSelectedMonthsAndIncludesHandlingInDetail() {
        var pending = service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, null, 1, 20, "202608", "202609", "待处理"), admin);
        assertEquals(2, pending.total());
        assertEquals("202608", pending.items().get(0).statisticsKey());
        assertEquals("待处理", pending.items().get(0).workOrderStatus());

        var serviceForUpdates = new ArrearsWorkOrderService(jdbc);
        var followUp = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 20, null, null, "跟进中"), managerA);
        assertEquals(1, followUp.total());
        serviceForUpdates.updateHandling(1, "已结清", null, managerA);
        serviceForUpdates.updateHandling(1, "跟进中", "已电话联系", managerA);
        var detail = service.detail(10, managerA);
        assertEquals(3, detail.handlingTimeline().size());
        assertEquals("STATUS", detail.handlingTimeline().get(0).type());
        assertEquals("STATUS", detail.handlingTimeline().get(1).type());
        assertEquals("FOLLOW_UP", detail.handlingTimeline().get(2).type());
        assertEquals("已电话联系", detail.handlingTimeline().get(2).note());
    }

    @Test
    void administratorWorkOrderExportHasFixedColumnsAndExcludesFollowUpContent() throws Exception {
        new ArrearsWorkOrderService(jdbc).updateHandling(1, "跟进中", "内部跟进说明不得导出", managerA);
        byte[] bytes = service.exportWorkOrders(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 20), admin);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(12, sheet.getRow(0).getPhysicalNumberOfCells());
            assertEquals("统计日期", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("最后处理时间", sheet.getRow(0).getCell(11).getStringCellValue());
            for (int column = 0; column < 12; column++) {
                assertFalse(sheet.getRow(0).getCell(column).getStringCellValue().contains("说明"));
            }
            String exported = sheet.getRow(1).getCell(9).getStringCellValue();
            assertEquals("跟进中", exported);
            assertFalse(sheet.getRow(1).toString().contains("内部跟进说明不得导出"));
        }
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.exportWorkOrders(ArrearsLedgerQueryService.Filters.defaults(), managerA));
    }

    @Test
    void validatesStatisticsKeyAndManagerFilter() {
        assertThrows(IllegalArgumentException.class, () -> service.list(new ArrearsLedgerQueryService.Filters(
                "202613", null, null, null, null, 1, 20), admin));
        assertThrows(IllegalArgumentException.class, () -> service.list(new ArrearsLedgerQueryService.Filters(
                "20260901", null, null, null, null, 1, 20), admin));
        assertThrows(IllegalArgumentException.class, () -> service.list(new ArrearsLedgerQueryService.Filters(
                null, null, null, null, 0L, 1, 20), admin));
    }

    @Test
    void detailReturnsRawFieldsAndTraceabilityForVisibleRow() {
        var detail = service.detail(10L, managerA);
        assertEquals(10L, detail.item().snapshotRecordId());
        assertEquals("arrears-current.xlsx", detail.originalFilename());
        assertEquals("欠费表", detail.sheetName());
        assertEquals(2, detail.sourceRowNumber());
        assertEquals("是", detail.rawFields().get(1).get("value").asText());
        assertEquals("MANUALLY_ASSIGNED", detail.assignmentStatus());
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> service.detail(12L, managerA));
        assertEquals("UNASSIGNED_EXCEPTION", service.detail(12L, admin).assignmentStatus());
    }

    @Test
    void composedDatasetCombinesBatchesAndAppliesManagerScopePerRow() {
        jdbc.update("INSERT INTO excel_import_row VALUES (991,99,'修正版',8,'202609:A-4',?,?)",
                "[{\"columnIndex\":1,\"header\":\"账户ID\",\"value\":\"A-4\"}]",
                "{\"账户ID\":\"A-4\"}");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (13,99,'ARREARS',991,'Excel经理丙',4)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES (13,99,'ARREARS','A-4','G-3','丙集团','修正产品',31.25,'20天')");
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", 13);

        var all = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 20), admin);
        assertEquals(4, all.total());
        assertTrue(all.items().stream().anyMatch(item -> item.accountId().equals("A-4")));
        var assigned = service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, "A-4", null, null, 1, 20), new SnapshotAccessScopeService.Scope(false, 4));
        assertEquals(1, assigned.total());
        assertEquals("修正产品", assigned.items().get(0).carryingProductName());
        assertEquals(0, service.list(new ArrearsLedgerQueryService.Filters(
                "202609", null, "A-4", null, null, 1, 20), managerA).total());

        var detail = service.detail(13, new SnapshotAccessScopeService.Scope(false, 4));
        assertEquals("arrears-revision.xlsx", detail.originalFilename());
        assertEquals("修正版", detail.sheetName());
        assertEquals(8, detail.sourceRowNumber());
        assertEquals("A-4", detail.rawFields().get(0).get("value").asText());
    }

    @Test
    void exportUsesTheFullFilteredResultAndAppendsEffectiveManagerWithoutComputedFields() throws Exception {
        for (long id = 30; id < 55; id++) {
            jdbc.update("INSERT INTO excel_import_row VALUES (?,100,'欠费表',?,'202609:A-X','[]','{}')",
                    id + 1000, (int) id);
            jdbc.update("INSERT INTO excel_snapshot_record VALUES (?,100,'ARREARS',?,'Excel甲',2)", id, id + 1000);
            jdbc.update("INSERT INTO arrears_snapshot VALUES (?,100,'ARREARS',?,'G-1','甲集团','产品甲',1,'1天')",
                    id, "A-X" + id);
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "ARREARS", "202609", id,
                    100, "202609:A-X" + id, "[]", "{}");
        }
        String crossBatchFields = "[{\"columnIndex\":1,\"header\":\"日期\",\"value\":\"202609\"},"
                + "{\"columnIndex\":7,\"header\":\"账户ID\",\"value\":\"A-CROSS\"},"
                + "{\"columnIndex\":8,\"header\":\"备注\",\"value\":\"第一份\"},"
                + "{\"columnIndex\":9,\"header\":\"备注\",\"value\":\"第二份\"},"
                + "{\"columnIndex\":10,\"header\":\"新增字段\",\"value\":\"跨批字段值\"}]";
        jdbc.update("INSERT INTO excel_import_row VALUES (1070,99,'修正版',60,'202609:A-CROSS',?,'{}')", crossBatchFields);
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (70,99,'ARREARS',1070,'Excel甲',2)");
        jdbc.update("INSERT INTO arrears_snapshot VALUES (70,99,'ARREARS','A-CROSS','G-1','甲集团','修正版产品',8,'2天')");
        jdbc.update("INSERT INTO excel_effective_row VALUES (70,'ARREARS','202609','202609:A-CROSS',99,70,?,'{}',?)",
                crossBatchFields, "{\"备注#1\":{\"batchId\":99,\"sheetName\":\"修正版\",\"sourceRowNumber\":60}}");
        jdbc.update("INSERT INTO excel_dataset_version_row VALUES (1000,'ARREARS','202609','202609:A-CROSS',70)");
        byte[] bytes = service.export(new ArrearsLedgerQueryService.Filters(
                "202609", "G-1", null, null, null, 1, 20), managerA);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("日期", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("是否达到停机标准", sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("账户ID", sheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("备注（第1列）", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("备注（第2列）", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("新增字段", sheet.getRow(0).getCell(5).getStringCellValue());
            assertEquals("当前有效客户经理", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals("来源批次", sheet.getRow(0).getCell(7).getStringCellValue());
            assertEquals("字段来源（JSON）", sheet.getRow(0).getCell(11).getStringCellValue());
            assertEquals("202609", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("是", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("经理甲", sheet.getRow(1).getCell(6).getStringCellValue());
            assertEquals("A-CROSS", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("第一份", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals("第二份", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("跨批字段值", sheet.getRow(2).getCell(5).getStringCellValue());
            assertEquals(99.0, sheet.getRow(2).getCell(7).getNumericCellValue());
            assertEquals("修正版", sheet.getRow(2).getCell(9).getStringCellValue());
            assertTrue(sheet.getRow(2).getCell(11).getStringCellValue().contains("备注#1"));
            assertEquals(27, sheet.getLastRowNum());
        }
    }

    @Test
    void rawExportHonorsWorkOrderStatusFilterWithoutAddingWorkOrderColumns() throws Exception {
        byte[] bytes = service.export(new ArrearsLedgerQueryService.Filters(
                "202609", null, null, null, null, 1, 20, null, null, "跟进中"), admin);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(1, sheet.getLastRowNum());
            assertEquals("202609", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("经理甲", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("来源批次", sheet.getRow(0).getCell(3).getStringCellValue());
        }
    }
}
