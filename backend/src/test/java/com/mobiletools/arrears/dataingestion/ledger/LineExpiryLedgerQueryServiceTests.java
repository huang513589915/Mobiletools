package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineExpiryLedgerQueryServiceTests {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private JdbcTemplate jdbc;
    private ObjectMapper mapper;
    private LineExpiryLedgerQueryService service;
    private SnapshotAccessScopeService.Scope admin;
    private SnapshotAccessScopeService.Scope managerA;
    private SnapshotAccessScopeService.Scope managerB;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:line-expiry-query;MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP ALL OBJECTS");
        jdbc.execute("CREATE TABLE app_user (id BIGINT PRIMARY KEY, username VARCHAR(100), real_name VARCHAR(100), role VARCHAR(30), enabled BOOLEAN)");
        jdbc.execute("CREATE TABLE excel_current_version (source_type VARCHAR(20), statistics_key VARCHAR(8), batch_id BIGINT, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), original_filename VARCHAR(255))");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT PRIMARY KEY, batch_id BIGINT, sheet_name VARCHAR(100), source_row_number INT, identity_key VARCHAR(255), raw_fields CLOB, normalized_fields CLOB)");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), import_row_id BIGINT, excel_manager_name VARCHAR(255), effective_manager_user_id BIGINT)");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT, source_type VARCHAR(20), member_service_number VARCHAR(100), group_code VARCHAR(100), group_name VARCHAR(255), product_code VARCHAR(100), product_name VARCHAR(255), billing_cutoff_date DATE)");
        jdbc.execute("CREATE TABLE line_renewal_work_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, statistics_key VARCHAR(8) NOT NULL, member_service_number VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT UNIQUE, status VARCHAR(20) NOT NULL DEFAULT '待处理', last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP, UNIQUE(statistics_key, member_service_number), CHECK(status IN ('待处理','跟进中','已续签','不续签')))");
        jdbc.execute("CREATE TABLE line_renewal_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CHECK(previous_status <> new_status))");
        jdbc.execute("CREATE TABLE line_renewal_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, note CLOB NOT NULL, created_by_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        ComposedLedgerTestFixtures.createSchema(jdbc);
        jdbc.update("INSERT INTO app_user VALUES (1,'admin','管理员','ADMIN',TRUE),(2,'manager-a','经理甲','CUSTOMER_MANAGER',TRUE),(3,'manager-b','经理乙','CUSTOMER_MANAGER',TRUE),(4,'manager-c','经理丙','CUSTOMER_MANAGER',TRUE)");
        jdbc.update("INSERT INTO excel_import_batch VALUES (100,'LINE_EXPIRY','20261001','line-current.xlsx'),(90,'LINE_EXPIRY','20260901','line-old.xlsx')");
        jdbc.update("INSERT INTO excel_current_version VALUES ('LINE_EXPIRY','20261001',100),('LINE_EXPIRY','20260901',90)");
        jdbc.update("INSERT INTO excel_import_row VALUES "
                + "(1000,100,'专线表',2,'20261001:S-1','[{\"columnIndex\":1,\"header\":\"统计日期\",\"value\":\"20261001\"},{\"columnIndex\":2,\"header\":\"成员状态\",\"value\":\"在用\"},{\"columnIndex\":3,\"header\":\"合同到期日\",\"value\":\"2027-01-01\"}]','{}'),"
                + "(1001,100,'专线表',3,'20261001:S-2','[]','{}'),(1002,100,'专线表',4,'20261001:S-3','[]','{}'),"
                + "(900,90,'专线表',2,'20260901:S-1','[]','{}')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (10,100,'LINE_EXPIRY',1000,'Excel甲',2),(11,100,'LINE_EXPIRY',1001,'Excel乙',3),(12,100,'LINE_EXPIRY',1002,'无匹配',NULL),(9,90,'LINE_EXPIRY',900,'Excel甲',2)");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES "
                + "(10,100,'LINE_EXPIRY','S-1','G-1','甲集团','P-1','产品一','2026-10-31'),"
                + "(11,100,'LINE_EXPIRY','S-2','G-1','甲集团','P-2','产品二','2026-10-21'),"
                + "(12,100,'LINE_EXPIRY','S-3','G-2','乙集团','P-3','产品三','2026-10-22'),"
                + "(9,90,'LINE_EXPIRY','S-1','G-1','甲集团','P-1','旧产品','2026-11-01')");
        jdbc.update("INSERT INTO line_renewal_work_order(statistics_key,member_service_number,current_snapshot_record_id,status,last_handled_by_user_id,last_handled_at) VALUES "
                + "('20261001','S-1',10,'跟进中',2,CURRENT_TIMESTAMP),('20261001','S-2',11,'待处理',NULL,NULL),"
                + "('20261001','S-3',12,'已续签',1,CURRENT_TIMESTAMP),('20260901','S-1',9,'不续签',2,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO line_renewal_status_history(work_order_id,previous_status,new_status,changed_by_user_id) VALUES (1,'待处理','跟进中',2),(3,'待处理','已续签',1),(4,'待处理','不续签',2)");
        ComposedLedgerTestFixtures.addPeriod(jdbc, "LINE_EXPIRY", "20261001", 1000);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", 10);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", 11);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", 12);
        ComposedLedgerTestFixtures.addPeriod(jdbc, "LINE_EXPIRY", "20260901", 900);
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 900, "LINE_EXPIRY", "20260901", 9);
        mapper = new ObjectMapper();
        service = new LineExpiryLedgerQueryService(jdbc, mapper, clock(TODAY));
        admin = new SnapshotAccessScopeService.Scope(true, 1);
        managerA = new SnapshotAccessScopeService.Scope(false, 2);
        managerB = new SnapshotAccessScopeService.Scope(false, 3);
    }

    @Test
    void defaultsToLatestCurrentDateAndSortsOverdueFirst() {
        var page = service.list(LineExpiryLedgerQueryService.Filters.defaults(), admin);
        assertEquals("20261001", page.statisticsKey());
        assertEquals(List.of("20261001", "20260901"), page.availableStatisticsKeys());
        assertEquals(List.of("待处理", "跟进中", "已续签", "不续签"), page.availableRenewalStatuses());
        assertEquals(TODAY, page.calculatedAt());
        assertEquals(List.of(11L, 12L, 10L), page.items().stream()
                .map(LineExpiryLedgerQueryService.Item::snapshotRecordId).toList());
        assertEquals(List.of(-1, 0, 9), page.items().stream()
                .map(LineExpiryLedgerQueryService.Item::remainingDays).toList());
    }

    @Test
    void defaultsToBusinessMonthAcrossUtcBoundaryAndAllowsAnExplicitHistoricalRange() {
        var afterLocalMonthBoundary = new LineExpiryLedgerQueryService(jdbc, mapper,
                Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZONE));

        var currentMonth = afterLocalMonthBoundary.list(LineExpiryLedgerQueryService.Filters.defaults(), admin);
        assertNull(currentMonth.statisticsKey());
        assertEquals(0, currentMonth.total());
        assertEquals(List.of("20261001", "20260901"), currentMonth.availableStatisticsKeys());

        var explicitOctoberRange = afterLocalMonthBoundary.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, "20261001", "20261031"), admin);
        assertEquals(3, explicitOctoberRange.total());
        assertTrue(explicitOctoberRange.items().stream().allMatch(item -> item.statisticsKey().equals("20261001")));
    }

    @Test
    void appliesInclusiveRemainingRangeAndGroupProductManagerFiltersBeforePagination() {
        var filtered = service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", "G-1", "P-", 2L, -1, 9, 1, 20), admin);
        assertEquals(1, filtered.total());
        assertEquals("S-1", filtered.items().get(0).memberServiceNumber());

        var overdue = service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, -1, -1, 1, 20), managerB);
        assertEquals(1, overdue.total());
        assertEquals(-1, overdue.items().get(0).remainingDays());
        assertEquals(0, service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, 1, 8, 1, 20), admin).total());
    }

    @Test
    void historicalRowsRecalculateAgainstTheCurrentClockAndIgnoreContractExpiry() {
        var historical = service.list(new LineExpiryLedgerQueryService.Filters(
                "20260901", null, null, null, null, null, 1, 20), managerA);
        assertEquals(10, historical.items().get(0).remainingDays());

        var tomorrow = new LineExpiryLedgerQueryService(jdbc, mapper, clock(TODAY.plusDays(1)));
        assertEquals(9, tomorrow.list(new LineExpiryLedgerQueryService.Filters(
                "20260901", null, null, null, null, null, 1, 20), managerA).items().get(0).remainingDays());
        var detail = service.detail(10, managerA);
        assertEquals(List.of("待处理", "跟进中", "已续签", "不续签"), detail.availableRenewalStatuses());
        assertEquals(9, detail.item().remainingDays());
        assertTrue(detail.rawFields().toString().contains("合同到期日"));
        assertThrows(AccessDeniedException.class, () -> service.detail(12, managerA));
        assertEquals("UNASSIGNED_EXCEPTION", service.detail(12, admin).assignmentStatus());
    }

    @Test
    void composedDatasetCombinesBatchesAndAppliesManagerScopePerLine() {
        jdbc.update("INSERT INTO excel_import_batch VALUES (80,'LINE_EXPIRY','20261001','line-revision.xlsx')");
        jdbc.update("INSERT INTO excel_import_row VALUES (1010,80,'修正版',12,'20261001:S-4','[]','{}')");
        jdbc.update("INSERT INTO excel_snapshot_record VALUES (13,80,'LINE_EXPIRY',1010,'Excel经理丙',4)");
        jdbc.update("INSERT INTO line_expiry_snapshot VALUES (13,80,'LINE_EXPIRY','S-4','G-3','丙集团','P-4','修正专线','2026-11-30')");
        ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", 13);

        var all = service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", null, null, null, null, null, 1, 20), admin);
        assertEquals(4, all.total());
        var assigned = service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", null, "修正专线", 4L, null, null, 1, 20), new SnapshotAccessScopeService.Scope(false, 4));
        assertEquals(1, assigned.total());
        assertEquals("修正专线", assigned.items().get(0).productName());
        assertEquals(0, service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", null, "修正专线", null, null, null, 1, 20), managerA).total());

        var detail = service.detail(13, new SnapshotAccessScopeService.Scope(false, 4));
        assertEquals("line-revision.xlsx", detail.originalFilename());
        assertEquals("修正版", detail.sheetName());
        assertEquals(12, detail.sourceRowNumber());
    }

    @Test
    void filtersAcrossInclusiveStatisticsDateRangeAndKeepsLatestDateInRangeAsSummary() {
        var ranged = service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, "20260901", "20261001"), admin);
        assertEquals("20261001", ranged.statisticsKey());
        assertEquals(4, ranged.total());
        assertEquals(2, service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, "20260901", "20261001"), managerA).total());

        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, "20261002", "20261001"), admin));
    }

    @Test
    void validatesDateRangePaginationAndManagerFilter() {
        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                "20261301", null, null, null, null, null, 1, 20), admin));
        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", null, null, null, 4, -1, 1, 20), admin));
        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, 0L, null, null, 1, 20), admin));
        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 10), admin));
    }

    @Test
    void filtersByRenewalStatusAndReturnsStatusHistoryInDetail() {
        var followUp = service.list(new LineExpiryLedgerQueryService.Filters(
                "20261001", null, null, null, null, null, 1, 20, null, null, "跟进中"), admin);
        assertEquals(1, followUp.total());
        assertEquals("跟进中", followUp.items().get(0).renewalStatus());
        assertNotNull(followUp.items().get(0).workOrderId());
        assertNotNull(followUp.items().get(0).lastHandledAt());
        var detail = service.detail(10, admin);
        assertEquals(1, detail.statusHistory().size());
        assertEquals("待处理", detail.statusHistory().get(0).previousStatus());
        assertEquals("跟进中", detail.statusHistory().get(0).newStatus());
        var workOrders = new LineRenewalWorkOrderService(jdbc);
        workOrders.updateHandling(1, "跟进中", "客户正在审批", managerA);
        var withNote = service.detail(10, admin);
        assertEquals(2, withNote.handlingTimeline().size());
        assertEquals("STATUS", withNote.handlingTimeline().get(0).type());
        assertEquals("FOLLOW_UP", withNote.handlingTimeline().get(1).type());
        assertEquals("客户正在审批", withNote.handlingTimeline().get(1).note());
        workOrders.updateStatus(1, "已续签", managerA);
        workOrders.updateStatus(1, "待处理", managerA);
        assertEquals(List.of("待处理->跟进中", "跟进中->已续签", "已续签->待处理"),
                service.detail(10, admin).statusHistory().stream()
                        .map(entry -> entry.previousStatus() + "->" + entry.newStatus()).toList());
        assertThrows(IllegalArgumentException.class, () -> service.list(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, null, null, "不存在"), admin));
    }

    @Test
    void exportUsesServerFiltersAndExcludesComputedRemainingDays() throws Exception {
        for (long id = 30; id < 55; id++) {
            long rowId = id + 1000;
            jdbc.update("INSERT INTO excel_import_row VALUES (?,100,'专线表',?,'20261001:S-X','[]','{}')",
                    rowId, (int) id);
            jdbc.update("INSERT INTO excel_snapshot_record VALUES (?,100,'LINE_EXPIRY',?,'Excel甲',2)", id, rowId);
            jdbc.update("INSERT INTO line_expiry_snapshot VALUES (?,100,'LINE_EXPIRY',?,'G-1','甲集团','P-X','产品扩展','2026-10-31')",
                    id, "S-X" + id);
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", id,
                    100, "20261001:S-X" + id, "[]", "{}");
        }
        byte[] bytes = service.export(new LineExpiryLedgerQueryService.Filters(
                "20261001", "G-1", null, 2L, null, null, 1, 20), managerA);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals("当前有效客户经理", sheet.getRow(0).getCell(3).getStringCellValue());
            assertEquals("来源批次", sheet.getRow(0).getCell(4).getStringCellValue());
            assertEquals("来源原始行号", sheet.getRow(0).getCell(7).getStringCellValue());
            assertEquals(26, sheet.getLastRowNum());
            assertNotEquals("剩余天数", sheet.getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    void exportsAdminRenewalReportWithFixedColumnsBlankHandlerFieldsAndManagerDenial() throws Exception {
        for (long id = 30; id < 55; id++) {
            long importRowId = id + 1000;
            String serviceNumber = "S-REPORT-" + id;
            jdbc.update("INSERT INTO excel_import_row VALUES (?,100,'line-sheet',?,'20261001:' || ?,'[]','{}')",
                    importRowId, (int) id, serviceNumber);
            jdbc.update("INSERT INTO excel_snapshot_record VALUES (?,100,'LINE_EXPIRY',?,'Excel甲',2)", id, importRowId);
            jdbc.update("INSERT INTO line_expiry_snapshot VALUES (?,100,'LINE_EXPIRY',?,'G-1','甲集团','P-R','报表产品','2026-10-31')",
                    id, serviceNumber);
            jdbc.update("INSERT INTO line_renewal_work_order(statistics_key,member_service_number,current_snapshot_record_id,status) VALUES ('20261001',?,?, '待处理')",
                    serviceNumber, id);
            ComposedLedgerTestFixtures.addCurrentSnapshot(jdbc, 1000, "LINE_EXPIRY", "20261001", id,
                    100, "20261001:" + serviceNumber, "[]", "{}");
        }
        byte[] bytes = service.exportRenewalReport(new LineExpiryLedgerQueryService.Filters(
                null, null, null, null, null, null, 1, 20, "20261001", "20261001", "待处理"), admin);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(12, sheet.getRow(0).getLastCellNum());
            assertEquals(List.of("统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码",
                            "Excel 原始客户经理", "当前有效客户经理", "计费截止日期", "状态", "最后处理人", "最后处理时间"),
                    java.util.stream.IntStream.range(0, 12)
                            .mapToObj(index -> sheet.getRow(0).getCell(index).getStringCellValue()).toList());
            assertEquals(26, sheet.getLastRowNum(), "report export must include rows beyond the 20-row page size");
            assertEquals("待处理", sheet.getRow(1).getCell(9).getStringCellValue());
            assertEquals("", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("", sheet.getRow(1).getCell(11).getStringCellValue());
        }
        assertThrows(AccessDeniedException.class, () -> service.exportRenewalReport(
                LineExpiryLedgerQueryService.Filters.defaults(), managerA));
    }

    private static Clock clock(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
    }
}
