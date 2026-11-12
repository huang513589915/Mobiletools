package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end acceptance of the two supplied workbooks on an isolated H2 database. */
class IndependentLedgerAcceptanceFlowTests {

    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private ObjectMapper mapper;
    private LedgerOriginalWorkbookStore workbookStore;
    private LedgerImportService imports;
    private LedgerBatchConfirmationService confirmations;
    private LedgerImportBatchQueryService batches;
    private AssignmentExceptionQueryService exceptions;
    private AssignmentManagementService assignments;
    private ArrearsLedgerQueryService arrears;
    private LineExpiryLedgerQueryService lines;

    @BeforeEach
    void setUp() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:ledger-acceptance-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        PlatformTransactionManager transactionManager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(transactionManager);
        mapper = new ObjectMapper().findAndRegisterModules();
        workbookStore = new LedgerOriginalWorkbookStore(tempDir.toString());
        createSchema();
        jdbc.update("INSERT INTO app_user(id,username,real_name,role,enabled) VALUES (1,'admin','管理员','ADMIN',TRUE)");

        imports = new LedgerImportService(jdbc, mapper, new LedgerWorkbookParser(), workbookStore);
        confirmations = new LedgerBatchConfirmationService(jdbc, mapper, null);
        batches = new LedgerImportBatchQueryService(jdbc, mapper);
        exceptions = new AssignmentExceptionQueryService(jdbc, mapper);
        assignments = new AssignmentManagementService(jdbc, exceptions);
        Clock septemberClock = Clock.fixed(Instant.parse("2026-09-15T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        arrears = new ArrearsLedgerQueryService(jdbc, mapper, septemberClock);
        lines = new LineExpiryLedgerQueryService(jdbc, mapper, septemberClock);
    }

    @Test
    void importsBothRealWorkbooksAndExercisesRevisionOwnershipHistoryDetailsAndExports() throws Exception {
        byte[] arrearsWorkbook = readSupplied("9月集团账户欠费明细月表.xlsx");
        byte[] lineWorkbook = readSupplied("10月底到期需续签明细.xlsx");

        var arrearsPreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "9月集团账户欠费明细月表.xlsx", arrearsWorkbook, "admin"));
        var linePreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "10月底到期需续签明细.xlsx", lineWorkbook, "admin"));
        assertEquals("PRECHECKED", arrearsPreview.status());
        assertEquals("PRECHECKED", linePreview.status());
        assertEquals(3, arrearsPreview.totalRows());
        assertEquals(5, linePreview.totalRows());
        assertEquals(3, arrearsPreview.warningRows());
        assertEquals(5, linePreview.warningRows());
        assertEquals(0, arrearsPreview.errorRows());
        assertEquals(0, linePreview.errorRows());
        assertArrayEquals(arrearsWorkbook, imports.originalWorkbook(arrearsPreview.batchId()).bytes());
        assertArrayEquals(lineWorkbook, imports.originalWorkbook(linePreview.batchId()).bytes());
        assertEquals(0, count("arrears_snapshot"));
        assertEquals(0, count("line_expiry_snapshot"));

        var arrearsConfirmed = inTransaction(() -> confirmations.confirm(arrearsPreview.batchId(), "admin"));
        var lineConfirmed = inTransaction(() -> confirmations.confirm(linePreview.batchId(), "admin"));
        assertEquals(3, arrearsConfirmed.assignmentExceptionRows());
        assertEquals(5, lineConfirmed.assignmentExceptionRows());
        assertEquals(3, count("arrears_snapshot"));
        assertEquals(5, count("line_expiry_snapshot"));

        var duplicate = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "duplicate.xlsx", arrearsWorkbook, "admin"));
        assertTrue(duplicate.duplicate());
        assertEquals("DUPLICATE", duplicate.status());
        assertEquals(3, count("arrears_snapshot"));
        var duplicateLine = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "duplicate-line.xlsx", lineWorkbook, "admin"));
        assertTrue(duplicateLine.duplicate());
        assertEquals("DUPLICATE", duplicateLine.status());
        assertEquals(5, count("line_expiry_snapshot"));

        long managerA = manager("manager-a", "经理甲");
        long managerB = manager("manager-b", "经理乙");
        var openArrears = exceptions.list("ARREARS", "202609", null, null, null, null,
                "OPEN", 1, 20).items();
        var openLines = exceptions.list("LINE_EXPIRY", "20260901", null, null, null, null,
                "OPEN", 1, 20).items();
        assertEquals(3, openArrears.size());
        assertEquals(5, openLines.size());
        inTransaction(() -> assignments.assign("ARREARS", openArrears.stream().map(AssignmentExceptionQueryService.ExceptionItem::snapshotRecordId).toList(),
                managerA, 1L, "欠费批量分配"));
        inTransaction(() -> assignments.assign("LINE_EXPIRY", openLines.stream().map(AssignmentExceptionQueryService.ExceptionItem::snapshotRecordId).toList(),
                managerB, 1L, "专线批量分配"));

        var adminArrears = arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1));
        var adminLines = lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1));
        assertEquals(3, adminArrears.total());
        assertEquals(5, adminLines.total());
        assertEquals(3, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerA)).total());
        assertEquals(0, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerB)).total());
        assertEquals(5, lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerB)).total());
        assertEquals(0, lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerA)).total());

        var selectedException = openArrears.get(0);
        inTransaction(() -> assignments.assign("ARREARS", List.of(selectedException.snapshotRecordId()), managerB, 1L, "验收改派"));
        var exceptionDetail = exceptions.detail(selectedException.id());
        assertEquals(2, exceptionDetail.assignmentHistory().size());
        assertEquals("REASSIGNMENT", exceptionDetail.assignmentHistory().get(1).changeType());
        assertEquals(managerB, exceptionDetail.assignmentHistory().get(1).newManagerUserId());
        assertEquals(2, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerA)).total());
        assertEquals(1, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerB)).total());
        assertEquals(5, lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerB)).total());

        var arrearsItem = arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerA)).items().get(0);
        var arrearsDetail = arrears.detail(arrearsItem.snapshotRecordId(), new SnapshotAccessScopeService.Scope(true, 1));
        var arrearsSourceRow = arrearsPreview.rows().stream()
                .filter(row -> row.identityKey().equals(arrearsDetail.identityKey()))
                .findFirst().orElseThrow();
        assertEquals(arrearsSourceRow.rawFields().size(), arrearsDetail.rawFields().size());
        assertEquals(arrearsSourceRow.rowNumber(), arrearsDetail.sourceRowNumber());
        assertEquals(arrearsSourceRow.rawFields().stream().map(LedgerWorkbookParser.RawField::header).toList(),
                jsonHeaders(arrearsDetail.rawFields()));
        var lineItem = adminLines.items().get(0);
        var lineDetail = lines.detail(lineItem.snapshotRecordId(), new SnapshotAccessScopeService.Scope(true, 1));
        var lineSourceRow = linePreview.rows().stream()
                .filter(row -> row.identityKey().equals(lineDetail.identityKey()))
                .findFirst().orElseThrow();
        assertEquals(lineSourceRow.rawFields().size(), lineDetail.rawFields().size());
        assertEquals(lineSourceRow.rowNumber(), lineDetail.sourceRowNumber());
        assertEquals(lineSourceRow.rawFields().stream().map(LedgerWorkbookParser.RawField::header).toList(),
                jsonHeaders(lineDetail.rawFields()));

        byte[] arrearsExport = arrears.export(new ArrearsLedgerQueryService.Filters(
                "202609", null, arrearsItem.accountId(), null, null, 1, 20),
                new SnapshotAccessScopeService.Scope(false, managerA));
        assertEquals(1, workbookRows(arrearsExport));
        byte[] lineExport = lines.export(LineExpiryLedgerQueryService.Filters.defaults(),
                new SnapshotAccessScopeService.Scope(false, managerB));
        assertEquals(5, workbookRows(lineExport));
        assertEquals(lineSourceRow.rawFields().size() + 6, workbookColumns(lineExport));

        byte[] revisedLine = changeFirstValue(lineWorkbook, "产品名称", "专线产品（修正版）");
        var lineRevisionPreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "line-revision.xlsx", revisedLine, "admin"));
        assertEquals(1, lineRevisionPreview.revision().changedRows());
        assertEquals(4, lineRevisionPreview.revision().unchangedRows());
        inTransaction(() -> confirmations.confirm(lineRevisionPreview.batchId(), "admin"));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM line_expiry_snapshot WHERE batch_id=?", Integer.class, linePreview.batchId()));
        assertEquals(lineRevisionPreview.batchId(), jdbc.queryForObject(
                "SELECT batch_id FROM excel_current_version WHERE source_type='LINE_EXPIRY' AND statistics_key='20260901'", Long.class));
        assertEquals(5, batches.detail(lineRevisionPreview.batchId(), 1, 20).rows().size());
        var lineDateBatches = batches.list("LINE_EXPIRY", "20260901", "CONFIRMED", 1, 20);
        var originalLineContribution = lineDateBatches.items().stream()
                .filter(batch -> batch.batchId() == linePreview.batchId()).findFirst().orElseThrow();
        var revisedLineContribution = lineDateBatches.items().stream()
                .filter(batch -> batch.batchId() == lineRevisionPreview.batchId()).findFirst().orElseThrow();
        assertEquals(4, originalLineContribution.contributingRows());
        assertEquals("PARTIAL", originalLineContribution.contributionStatus());
        assertEquals(1, revisedLineContribution.contributingRows());
        assertEquals("PARTIAL", revisedLineContribution.contributionStatus());

        byte[] revisedArrears = changeFirstValue(arrearsWorkbook, "集团名称", "集团名称（修正版）");
        var revisionPreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "arrears-revision.xlsx", revisedArrears, "admin"));
        assertFalse(revisionPreview.duplicate());
        assertEquals(1, revisionPreview.revision().changedRows());
        assertEquals(2, revisionPreview.revision().unchangedRows());
        inTransaction(() -> confirmations.confirm(revisionPreview.batchId(), "admin"));
        assertEquals(revisionPreview.batchId(), jdbc.queryForObject(
                "SELECT batch_id FROM excel_current_version WHERE source_type='ARREARS' AND statistics_key='202609'", Long.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM arrears_snapshot WHERE batch_id=?", Integer.class, arrearsPreview.batchId()));
        assertEquals(3, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1)).total());
        assertEquals(2, arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(false, managerA)).total());
        var dateBatches = batches.list("ARREARS", "202609", "CONFIRMED", 1, 20);
        var originalArrearsContribution = dateBatches.items().stream()
                .filter(batch -> batch.batchId() == arrearsPreview.batchId()).findFirst().orElseThrow();
        var revisedArrearsContribution = dateBatches.items().stream()
                .filter(batch -> batch.batchId() == revisionPreview.batchId()).findFirst().orElseThrow();
        assertEquals(2, originalArrearsContribution.contributingRows());
        assertEquals("PARTIAL", originalArrearsContribution.contributionStatus());
        assertEquals(1, revisedArrearsContribution.contributingRows());
        assertEquals("PARTIAL", revisedArrearsContribution.contributionStatus());
        var revisionDetail = batches.detail(revisionPreview.batchId(), 1, 20);
        assertEquals(3, revisionDetail.rows().size());
        assertEquals(3, revisionDetail.revisionDifferenceTotal());

        byte[] historicalArrears = changeAllValues(arrearsWorkbook, "日期", "202608");
        var historicalPreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "arrears-history.xlsx", historicalArrears, "admin"));
        assertEquals("202608", historicalPreview.statisticsKey());
        inTransaction(() -> confirmations.confirm(historicalPreview.batchId(), "admin"));
        var latest = arrears.list(ArrearsLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1));
        var historical = arrears.list(new ArrearsLedgerQueryService.Filters("202608", null, null, null, null, 1, 20),
                new SnapshotAccessScopeService.Scope(true, 1));
        assertEquals("202609", latest.statisticsKey());
        assertTrue(latest.availableStatisticsKeys().containsAll(List.of("202609", "202608")));
        assertEquals("202608", historical.statisticsKey());
        assertEquals(3, historical.total());
        assertEquals("20260901", lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1)).statisticsKey());
        assertEquals(0, arrears.list(new ArrearsLedgerQueryService.Filters("202608", null, null, null, null, 1, 20),
                new SnapshotAccessScopeService.Scope(false, managerA)).total());

        byte[] historicalLineWorkbook = changeAllValues(lineWorkbook, "统计日期", "20260815");
        var historicalLinePreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "line-history.xlsx", historicalLineWorkbook, "admin"));
        assertEquals("20260815", historicalLinePreview.statisticsKey());
        inTransaction(() -> confirmations.confirm(historicalLinePreview.batchId(), "admin"));
        var latestLines = lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1));
        var historicalLines = lines.list(new LineExpiryLedgerQueryService.Filters(
                        "20260815", null, null, null, null, null, 1, 20),
                new SnapshotAccessScopeService.Scope(true, 1));
        assertEquals("20260901", latestLines.statisticsKey());
        assertTrue(latestLines.availableStatisticsKeys().containsAll(List.of("20260901", "20260815")));
        assertEquals("20260815", historicalLines.statisticsKey());
        assertEquals(5, historicalLines.total());

        byte[] revisedHistoricalLine = changeFirstValue(historicalLineWorkbook, "产品名称", "历史专线修正");
        var revisedHistoricalPreview = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "line-history-revision.xlsx", revisedHistoricalLine, "admin"));
        assertEquals("20260815", revisedHistoricalPreview.statisticsKey());
        assertFalse(revisedHistoricalPreview.duplicate());
        assertEquals(1, revisedHistoricalPreview.revision().changedRows());
        inTransaction(() -> confirmations.confirm(revisedHistoricalPreview.batchId(), "admin"));
        var stillLatest = lines.list(LineExpiryLedgerQueryService.Filters.defaults(), new SnapshotAccessScopeService.Scope(true, 1));
        var revisedHistorical = lines.list(new LineExpiryLedgerQueryService.Filters(
                        "20260815", null, null, null, null, null, 1, 20),
                new SnapshotAccessScopeService.Scope(true, 1));
        assertEquals("20260901", stillLatest.statisticsKey());
        assertEquals(5, stillLatest.total());
        assertEquals("20260815", revisedHistorical.statisticsKey());
        assertEquals(5, revisedHistorical.total());
        String revisedIdentity = revisedHistoricalPreview.rows().get(0).identityKey();
        var revisedHistoricalRow = revisedHistorical.items().stream()
                .filter(item -> revisedIdentity.endsWith(":" + item.memberServiceNumber())).findFirst().orElseThrow();
        var revisedHistoricalDetail = lines.detail(revisedHistoricalRow.snapshotRecordId(),
                new SnapshotAccessScopeService.Scope(true, 1));
        assertEquals("历史专线修正", fieldValue(revisedHistoricalDetail.rawFields(), "产品名称"));
        assertEquals(revisedHistoricalPreview.batchId(), revisedHistoricalDetail.batchId());
    }

    @Test
    void cancelledUploadAndItsDuplicateAttemptDoNotBlockReuploadingTheSameFile() throws Exception {
        byte[] workbook = readSupplied("9月集团账户欠费明细月表.xlsx");
        var first = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "9月集团账户欠费明细月表.xlsx", workbook, "admin"));
        var duplicate = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "9月集团账户欠费明细月表.xlsx", workbook, "admin"));
        assertEquals("PRECHECKED", first.status());
        assertEquals("DUPLICATE", duplicate.status());

        var cancellations = new LedgerBatchCancellationService(jdbc);
        inTransaction(() -> cancellations.cancel(first.batchId(), "admin"));
        var retry = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "9月集团账户欠费明细月表.xlsx", workbook, "admin"));

        assertEquals("PRECHECKED", retry.status());
        assertFalse(retry.duplicate());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, first.batchId()));
        assertEquals("DUPLICATE", jdbc.queryForObject("SELECT batch_status FROM excel_import_batch WHERE id=?", String.class, duplicate.batchId()));
        assertEquals(0, count("arrears_snapshot"));

        jdbc.update("UPDATE excel_import_batch SET batch_status='FAILED' WHERE id=?", retry.batchId());
        var retryAfterFailure = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "9月集团账户欠费明细月表.xlsx", workbook, "admin"));
        assertEquals("PRECHECKED", retryAfterFailure.status());
        assertFalse(retryAfterFailure.duplicate());
    }

    @Test
    void partialArrearsAndLineMergesKeepOmittedRowsAndExposeLatestDataEverywhere() throws Exception {
        SnapshotAccessScopeService.Scope admin = new SnapshotAccessScopeService.Scope(true, 1);
        var arrearsBase = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "arrears-base.xlsx", workbook("arrears", new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "期末欠费金额", "账龄"},
                        new String[]{"202609", "区一", "G-1", "集团一", "G-1", "张三", "A-1", "100", "30天"},
                        new String[]{"202609", "区二", "G-2", "集团二", "G-2", "张三", "A-2", "200", "60天"},
                        new String[]{"202609", "区四", "G-4", "集团四", "G-4", "张三", "A-4", "400", "120天"}), "admin"));
        var lineBase = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "line-base.xlsx", workbook("line", new String[]{"统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码", "客户经理姓名", "计费截止日期"},
                        new String[]{"20260915", "G-1", "集团一", "P-1", "专线一", "S-1", "张三", "20261001"},
                        new String[]{"20260915", "G-2", "集团二", "P-2", "专线二", "S-2", "张三", "20261002"},
                        new String[]{"20260915", "G-4", "集团四", "P-4", "专线四", "S-4", "张三", "20261004"}), "admin"));
        assertEquals("PRECHECKED", arrearsBase.status(), arrearsBase.issues().toString());
        assertEquals("PRECHECKED", lineBase.status(), lineBase.issues().toString());
        inTransaction(() -> confirmations.confirm(arrearsBase.batchId(), "admin"));
        inTransaction(() -> confirmations.confirm(lineBase.batchId(), "admin"));

        var arrearsPartial = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.ARREARS,
                "arrears-partial.xlsx", workbook("arrears", new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "期末欠费金额", "账龄"},
                        new String[]{"202609", "区一", "G-1", "集团一修正", "G-1", "张三", "A-1", "150", "30天"},
                        new String[]{"202609", "区二", "G-2", "集团二", "G-2", "张三", "A-2", "200", "60天"},
                        new String[]{"202609", "区三", "G-3", "集团三新增", "G-3", "张三", "A-3", "300", "90天"}), "admin"));
        var linePartial = inTransaction(() -> imports.precheck(LedgerWorkbookParser.SourceType.LINE_EXPIRY,
                "line-partial.xlsx", workbook("line", new String[]{"统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码", "客户经理姓名", "计费截止日期"},
                        new String[]{"20260915", "G-1", "集团一修正", "P-1", "专线一修正", "S-1", "张三", "20261010"},
                        new String[]{"20260915", "G-2", "集团二", "P-2", "专线二", "S-2", "张三", "20261002"},
                        new String[]{"20260915", "G-3", "集团三新增", "P-3", "专线三新增", "S-3", "张三", "20261003"}), "admin"));
        assertEquals("PRECHECKED", arrearsPartial.status(), arrearsPartial.issues().toString());
        assertEquals("PRECHECKED", linePartial.status(), linePartial.issues().toString());

        assertEquals(1, arrearsPartial.revision().addedRows());
        assertEquals(1, arrearsPartial.revision().changedRows());
        assertEquals(1, arrearsPartial.revision().unchangedRows());
        assertEquals(1, arrearsPartial.revision().retainedRows());
        assertEquals(1, linePartial.revision().addedRows());
        assertEquals(1, linePartial.revision().changedRows());
        assertEquals(1, linePartial.revision().unchangedRows());
        assertEquals(1, linePartial.revision().retainedRows());
        inTransaction(() -> confirmations.confirm(arrearsPartial.batchId(), "admin"));
        inTransaction(() -> confirmations.confirm(linePartial.batchId(), "admin"));

        var arrearsPage = arrears.list(ArrearsLedgerQueryService.Filters.defaults(), admin);
        var linePage = lines.list(LineExpiryLedgerQueryService.Filters.defaults(), admin);
        assertEquals(4, arrearsPage.total());
        assertEquals(4, linePage.total());
        assertEquals(1, arrears.list(new ArrearsLedgerQueryService.Filters(
                "202609", "集团一修正", null, null, null, 1, 20), admin).total());
        assertEquals(1, lines.list(new LineExpiryLedgerQueryService.Filters(
                "20260915", "集团一修正", "专线一修正", null, null, null, 1, 20), admin).total());

        var updatedArrears = arrearsPage.items().stream().filter(item -> "A-1".equals(item.accountId())).findFirst().orElseThrow();
        var unchangedArrears = arrearsPage.items().stream().filter(item -> "A-2".equals(item.accountId())).findFirst().orElseThrow();
        var retainedArrears = arrearsPage.items().stream().filter(item -> "A-4".equals(item.accountId())).findFirst().orElseThrow();
        var updatedArrearsDetail = arrears.detail(updatedArrears.snapshotRecordId(), admin);
        var unchangedArrearsDetail = arrears.detail(unchangedArrears.snapshotRecordId(), admin);
        var retainedArrearsDetail = arrears.detail(retainedArrears.snapshotRecordId(), admin);
        assertEquals(arrearsPartial.batchId(), updatedArrearsDetail.batchId());
        assertEquals(arrearsBase.batchId(), unchangedArrearsDetail.batchId());
        assertEquals(arrearsBase.batchId(), retainedArrearsDetail.batchId());
        assertEquals("集团一修正", fieldValue(updatedArrearsDetail.rawFields(), "集团名称"));
        assertEquals("集团二", fieldValue(unchangedArrearsDetail.rawFields(), "集团名称"));
        assertEquals("集团四", fieldValue(retainedArrearsDetail.rawFields(), "集团名称"));

        var updatedLine = linePage.items().stream().filter(item -> "S-1".equals(item.memberServiceNumber())).findFirst().orElseThrow();
        var unchangedLine = linePage.items().stream().filter(item -> "S-2".equals(item.memberServiceNumber())).findFirst().orElseThrow();
        var retainedLine = linePage.items().stream().filter(item -> "S-4".equals(item.memberServiceNumber())).findFirst().orElseThrow();
        var updatedLineDetail = lines.detail(updatedLine.snapshotRecordId(), admin);
        var unchangedLineDetail = lines.detail(unchangedLine.snapshotRecordId(), admin);
        var retainedLineDetail = lines.detail(retainedLine.snapshotRecordId(), admin);
        assertEquals(linePartial.batchId(), updatedLineDetail.batchId());
        assertEquals(lineBase.batchId(), unchangedLineDetail.batchId());
        assertEquals(lineBase.batchId(), retainedLineDetail.batchId());
        assertEquals("专线一修正", fieldValue(updatedLineDetail.rawFields(), "产品名称"));
        assertEquals("专线四", fieldValue(retainedLineDetail.rawFields(), "产品名称"));
        assertEquals("专线二", fieldValue(unchangedLineDetail.rawFields(), "产品名称"));

        var arrearsBatches = batches.list("ARREARS", "202609", "CONFIRMED", 1, 20).items();
        var lineBatches = batches.list("LINE_EXPIRY", "20260915", "CONFIRMED", 1, 20).items();
        assertEquals(2, arrearsBatches.stream().filter(batch -> batch.batchId() == arrearsBase.batchId())
                .findFirst().orElseThrow().contributingRows());
        assertEquals("PARTIAL", arrearsBatches.stream().filter(batch -> batch.batchId() == arrearsBase.batchId())
                .findFirst().orElseThrow().contributionStatus());
        assertEquals(2, lineBatches.stream().filter(batch -> batch.batchId() == lineBase.batchId())
                .findFirst().orElseThrow().contributingRows());
        assertEquals("PARTIAL", lineBatches.stream().filter(batch -> batch.batchId() == lineBase.batchId())
                .findFirst().orElseThrow().contributionStatus());
        assertEquals(2, lineBatches.stream().filter(batch -> batch.batchId() == linePartial.batchId())
                .findFirst().orElseThrow().contributingRows());
        assertEquals(4, batches.detail(arrearsPartial.batchId(), 1, 20).revisionDifferenceTotal());
        assertEquals(4, batches.detail(linePartial.batchId(), 1, 20).revisionDifferenceTotal());

        byte[] arrearsExport = arrears.export(ArrearsLedgerQueryService.Filters.defaults(), admin);
        byte[] lineExport = lines.export(LineExpiryLedgerQueryService.Filters.defaults(), admin);
        assertEquals(4, workbookRows(arrearsExport));
        assertEquals(4, workbookRows(lineExport));
        assertTrue(workbookContains(arrearsExport, "集团一修正"));
        assertTrue(workbookContains(arrearsExport, "集团二"));
        assertTrue(workbookContains(lineExport, "专线一修正"));
        assertTrue(workbookContains(lineExport, "专线四"));
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE app_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, username VARCHAR(100) NOT NULL, real_name VARCHAR(100) NOT NULL, role VARCHAR(30) NOT NULL, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE excel_source_file (id BIGINT AUTO_INCREMENT PRIMARY KEY, sha256 VARCHAR(64) NOT NULL UNIQUE, storage_key VARCHAR(512) NOT NULL, byte_size BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE excel_original_file_purge_queue (id BIGINT AUTO_INCREMENT PRIMARY KEY, operation_id VARCHAR(36) NOT NULL, storage_key VARCHAR(80) NOT NULL, attempt_count INT NOT NULL DEFAULT 0, last_error_code VARCHAR(60), next_attempt_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_import_batch (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8), original_filename VARCHAR(255) NOT NULL, source_file_id BIGINT, uploaded_by_user_id BIGINT NOT NULL, batch_status VARCHAR(20) NOT NULL, revision_of_batch_id BIGINT, preview_base_version_id BIGINT, total_rows INT NOT NULL DEFAULT 0, valid_rows INT NOT NULL DEFAULT 0, warning_rows INT NOT NULL DEFAULT 0, error_rows INT NOT NULL DEFAULT 0, added_rows INT NOT NULL DEFAULT 0, removed_rows INT NOT NULL DEFAULT 0, changed_rows INT NOT NULL DEFAULT 0, unchanged_rows INT NOT NULL DEFAULT 0, retained_rows INT NOT NULL DEFAULT 0, erased_rows INT NOT NULL DEFAULT 0, original_file_purge_operation_id VARCHAR(36), original_file_purge_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', validation_summary CLOB, uploaded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, confirmed_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_import_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, sheet_name VARCHAR(100) NOT NULL, source_row_number INT NOT NULL, identity_key VARCHAR(255), row_hash VARCHAR(64), validation_status VARCHAR(20) NOT NULL, raw_fields CLOB NOT NULL, normalized_fields CLOB, errors CLOB, warnings CLOB, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_revision_diff (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, identity_key VARCHAR(255) NOT NULL, change_type VARCHAR(20) NOT NULL, previous_import_row_id BIGINT, candidate_import_row_id BIGINT, previous_fields CLOB, candidate_fields CLOB, changed_fields CLOB)");
        jdbc.execute("CREATE TABLE excel_current_version (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, batch_id BIGINT NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_snapshot_record (id BIGINT AUTO_INCREMENT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, import_row_id BIGINT NOT NULL UNIQUE, excel_manager_name VARCHAR(255), effective_manager_user_id BIGINT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE arrears_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, account_id VARCHAR(100) NOT NULL, group_code VARCHAR(100) NOT NULL, group_name VARCHAR(255) NOT NULL, carrying_product_name VARCHAR(255), ending_arrears_amount DECIMAL(20,4) NOT NULL, aging_value VARCHAR(100), UNIQUE(batch_id,account_id))");
        jdbc.execute("CREATE TABLE arrears_work_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, statistics_key VARCHAR(6) NOT NULL, account_id VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT UNIQUE, status VARCHAR(20) NOT NULL DEFAULT '待处理', last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP, UNIQUE(statistics_key,account_id), CHECK(status IN ('待处理','跟进中','已结清'))) ");
        jdbc.execute("CREATE TABLE arrears_work_order_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CHECK(previous_status <> new_status))");
        jdbc.execute("CREATE TABLE arrears_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, note CLOB NOT NULL, created_by_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE line_expiry_snapshot (snapshot_record_id BIGINT PRIMARY KEY, batch_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, member_service_number VARCHAR(100) NOT NULL, group_code VARCHAR(100) NOT NULL, group_name VARCHAR(255) NOT NULL, product_code VARCHAR(100), product_name VARCHAR(255), billing_cutoff_date DATE NOT NULL, UNIQUE(batch_id,member_service_number))");
        jdbc.execute("CREATE TABLE assignment_exception (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT NOT NULL, exception_type VARCHAR(30) NOT NULL, reason VARCHAR(1000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'OPEN', resolution VARCHAR(1000), handled_by_user_id BIGINT, handled_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE assignment_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, snapshot_record_id BIGINT NOT NULL, previous_manager_user_id BIGINT, new_manager_user_id BIGINT NOT NULL, change_type VARCHAR(30) NOT NULL, changed_by_user_id BIGINT NOT NULL, reason VARCHAR(1000), changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE operation_audit_log (id BIGINT AUTO_INCREMENT PRIMARY KEY, operator_user_id BIGINT, operation_type VARCHAR(60), target_type VARCHAR(60), target_id BIGINT, result VARCHAR(30), detail VARCHAR(2000))");
        jdbc.execute("CREATE TABLE line_renewal_work_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, statistics_key VARCHAR(8) NOT NULL, member_service_number VARCHAR(100) NOT NULL, current_snapshot_record_id BIGINT UNIQUE, status VARCHAR(20) NOT NULL DEFAULT '待处理', last_handled_by_user_id BIGINT, last_handled_at TIMESTAMP, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, UNIQUE(statistics_key, member_service_number), CHECK(status IN ('待处理','跟进中','已续签','不续签')))");
        jdbc.execute("CREATE TABLE line_renewal_status_history (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, previous_status VARCHAR(20) NOT NULL, new_status VARCHAR(20) NOT NULL, changed_by_user_id BIGINT NOT NULL, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, CHECK(previous_status <> new_status))");
        jdbc.execute("CREATE TABLE line_renewal_work_order_follow_up (id BIGINT AUTO_INCREMENT PRIMARY KEY, work_order_id BIGINT NOT NULL, note CLOB NOT NULL, created_by_user_id BIGINT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_dataset_version (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, source_batch_id BIGINT NOT NULL UNIQUE, based_on_version_id BIGINT, created_by_user_id BIGINT NOT NULL, added_rows INT NOT NULL, updated_rows INT NOT NULL, unchanged_rows INT NOT NULL, retained_rows INT NOT NULL, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE excel_dataset_period (source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, current_version_id BIGINT, changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(source_type,statistics_key))");
        jdbc.execute("CREATE TABLE excel_effective_row (id BIGINT AUTO_INCREMENT PRIMARY KEY, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, identity_key VARCHAR(255) NOT NULL, latest_batch_id BIGINT NOT NULL, snapshot_record_id BIGINT NOT NULL UNIQUE, raw_fields CLOB NOT NULL, normalized_fields CLOB NOT NULL, field_provenance CLOB NOT NULL)");
        jdbc.execute("CREATE TABLE excel_dataset_version_row (dataset_version_id BIGINT NOT NULL, source_type VARCHAR(20) NOT NULL, statistics_key VARCHAR(8) NOT NULL, identity_key VARCHAR(255) NOT NULL, effective_row_id BIGINT NOT NULL, PRIMARY KEY(dataset_version_id,identity_key), UNIQUE(dataset_version_id,effective_row_id))");
        jdbc.execute("CREATE VIEW excel_current_dataset AS SELECT p.source_type,p.statistics_key,p.current_version_id,e.latest_batch_id AS batch_id,e.snapshot_record_id,e.id AS effective_row_id,m.identity_key,e.raw_fields AS effective_raw_fields,e.normalized_fields AS effective_normalized_fields,e.field_provenance,p.changed_by_user_id,p.changed_at FROM excel_dataset_period p JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id JOIN excel_effective_row e ON e.id=m.effective_row_id");
    }

    private long manager(String username, String realName) {
        jdbc.update("INSERT INTO app_user(username,real_name,role,enabled) VALUES (?,?, 'CUSTOMER_MANAGER',TRUE)", username, realName);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private <T> T inTransaction(ThrowingSupplier<T> operation) throws Exception {
        return transactions.execute(status -> {
            try {
                return operation.get();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private byte[] readSupplied(String filename) throws Exception {
        return Files.readAllBytes(Path.of("..", "excel", filename).toAbsolutePath().normalize());
    }

    private byte[] workbook(String sheetName, String[] headers, String[]... rows) throws Exception {
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < rows[rowIndex].length; column++) {
                    row.createCell(column).setCellValue(rows[rowIndex][column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private String fieldValue(com.fasterxml.jackson.databind.JsonNode fields, String header) {
        for (var field : fields) {
            if (header.equals(field.path("header").asText())) return field.path("value").asText();
        }
        return null;
    }

    private boolean workbookContains(byte[] bytes, String expected) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var formatter = new DataFormatter();
            for (var row : workbook.getSheetAt(0)) {
                for (var cell : row) {
                    if (expected.equals(formatter.formatCellValue(cell))) return true;
                }
            }
            return false;
        }
    }

    private byte[] changeFirstValue(byte[] input, String headerName, String newValue) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(input));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var formatter = new DataFormatter();
            var header = sheet.getRow(sheet.getFirstRowNum());
            int column = -1;
            for (int index = 0; index < header.getLastCellNum(); index++) {
                if (header.getCell(index) != null && headerName.equals(formatter.formatCellValue(header.getCell(index)).strip())) {
                    column = index;
                    break;
                }
            }
            assertTrue(column >= 0, "Missing header: " + headerName);
            var row = sheet.getRow(sheet.getFirstRowNum() + 1);
            row.getCell(column).setCellValue(newValue);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] changeAllValues(byte[] input, String headerName, String newValue) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(input));
             var output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var formatter = new DataFormatter();
            var header = sheet.getRow(sheet.getFirstRowNum());
            int column = -1;
            for (int index = 0; index < header.getLastCellNum(); index++) {
                if (header.getCell(index) != null && headerName.equals(formatter.formatCellValue(header.getCell(index)).strip())) {
                    column = index;
                    break;
                }
            }
            assertTrue(column >= 0, "Missing header: " + headerName);
            for (int rowIndex = sheet.getFirstRowNum() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                if (row != null) row.getCell(column).setCellValue(newValue);
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private int workbookRows(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            return Math.max(0, workbook.getSheetAt(0).getLastRowNum());
        }
    }

    private int workbookColumns(byte[] bytes) throws Exception {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            return workbook.getSheetAt(0).getRow(0).getLastCellNum();
        }
    }

    private List<String> jsonHeaders(com.fasterxml.jackson.databind.JsonNode rawFields) {
        List<String> headers = new ArrayList<>();
        rawFields.forEach(field -> headers.add(field.path("header").asText()));
        return headers;
    }
}
