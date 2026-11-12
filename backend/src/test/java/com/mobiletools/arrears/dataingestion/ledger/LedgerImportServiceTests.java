package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LedgerImportServiceTests {

    @TempDir Path tempDir;

    private JdbcTemplate jdbc;
    private LedgerOriginalWorkbookStore store;
    private LedgerImportService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new LedgerOriginalWorkbookStore(tempDir.toString());
        service = new LedgerImportService(jdbc, new ObjectMapper().findAndRegisterModules(),
                new LedgerWorkbookParser(), store);
        when(jdbc.queryForObject(eq("SELECT id FROM excel_source_file WHERE sha256=?"), eq(Long.class), anyString()))
                .thenReturn(21L);
        when(jdbc.queryForObject("SELECT byte_size FROM excel_source_file WHERE id=?", Long.class, 21L))
                .thenAnswer(invocation -> (long) activeBytes.length);
        when(jdbc.queryForObject("SELECT id FROM excel_source_file WHERE id=? FOR UPDATE", Long.class, 21L))
                .thenReturn(21L);
        when(jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, "admin"))
                .thenReturn(8L);
        when(jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class))
                .thenReturn(101L, 102L, 103L);
    }

    private byte[] activeBytes;

    @Test
    void originalBytesAreStoredAndDuplicateAttemptsKeepSeparateBatches() throws Exception {
        byte[] bytes = arrearsWorkbook();
        activeBytes = bytes;
        when(jdbc.queryForObject(contains("batch_status NOT IN ('CANCELLED', 'FAILED')"), eq(Integer.class),
                eq("ARREARS"), eq(21L), anyLong())).thenReturn(0, 1);
        when(jdbc.queryForObject(contains("batch_status='PRECHECKED'"), eq(Integer.class),
                eq("ARREARS"), eq(21L), anyLong())).thenReturn(0, 1);

        LedgerImportService.UploadPreview first = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "C:\\uploads\\arrears.xlsx", bytes, "admin");
        LedgerImportService.UploadPreview second = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "arrears-copy.xlsx", bytes, "admin");

        assertEquals(101L, first.batchId());
        assertEquals("PRECHECKED", first.status());
        assertFalse(first.duplicate());
        assertEquals(1, first.warningRows());
        assertEquals("MANAGER_NO_MATCH", first.rows().get(0).issues().get(0).code());
        assertEquals(102L, second.batchId());
        assertEquals("DUPLICATE", second.status());
        assertTrue(second.duplicate());
        assertEquals(1, first.totalRows());

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(hash, first.fileSha256());
        String storageKey = store.store(bytes).storageKey();
        when(jdbc.queryForList(contains("FROM excel_import_batch b"), eq(first.batchId())))
                .thenReturn(List.of(Map.of("original_filename", "arrears.xlsx",
                        "storage_key", storageKey, "sha256", hash)));
        when(jdbc.queryForList(contains("FROM excel_import_batch b"), eq(second.batchId())))
                .thenReturn(List.of(Map.of("original_filename", "arrears-copy.xlsx",
                        "storage_key", storageKey, "sha256", hash)));
        LedgerImportService.OriginalWorkbook original = service.originalWorkbook(first.batchId());
        LedgerImportService.OriginalWorkbook duplicateOriginal = service.originalWorkbook(second.batchId());
        assertArrayEquals(bytes, original.bytes());
        assertArrayEquals(bytes, duplicateOriginal.bytes());
        assertEquals(hash, original.sha256());
        assertEquals("arrears.xlsx", original.filename());
        verify(jdbc, times(2)).update(contains("INSERT INTO excel_import_batch"), any(), any(), any(), any());
        verify(jdbc, times(2)).update(contains("INSERT INTO excel_import_row"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateRealNameAccountsAreShownAsWarningAndDoNotBlockConfirmation() throws Exception {
        byte[] bytes = arrearsWorkbook();
        activeBytes = bytes;
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM excel_import_batch"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForList(contains("SELECT id FROM app_user"), eq(Long.class), eq("张伟")))
                .thenReturn(List.of(12L, 13L));

        LedgerImportService.UploadPreview result = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "same-name.xlsx", bytes, "admin");

        assertEquals("PRECHECKED", result.status());
        assertEquals(0, result.errorRows());
        assertEquals(1, result.warningRows());
        assertEquals("MANAGER_MULTIPLE_MATCHES", result.rows().get(0).issues().get(0).code());
        assertTrue(result.rows().get(0).issues().get(0).message().contains("存在多个真实姓名"));
    }

    @Test
    void suppliedWorkbooksPrecheckWithoutBlockingAndKeepTheirExactBytes() throws Exception {
        byte[] arrears = supplied("9月集团账户欠费明细月表.xlsx");
        byte[] lines = supplied("10月底到期需续签明细.xlsx");
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM excel_import_batch"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM excel_import_batch"), eq(Integer.class),
                eq("LINE_EXPIRY"), eq(21L), eq(102L))).thenReturn(0);
        when(jdbc.queryForList(contains("FROM excel_current_version"), anyString(), anyString()))
                .thenReturn(List.of(), List.of());
        when(jdbc.queryForList(contains("SELECT id FROM app_user"), eq(Long.class), anyString()))
                .thenReturn(List.of());

        activeBytes = arrears;
        LedgerImportService.UploadPreview arrearsPreview = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "9月集团账户欠费明细月表.xlsx", arrears, "admin");
        activeBytes = lines;
        LedgerImportService.UploadPreview linePreview = service.precheck(
                LedgerWorkbookParser.SourceType.LINE_EXPIRY, "10月底到期需续签明细.xlsx", lines, "admin");

        assertEquals("202609", arrearsPreview.statisticsKey());
        assertEquals(3, arrearsPreview.totalRows());
        assertEquals(0, arrearsPreview.errorRows());
        assertEquals(3, arrearsPreview.warningRows());
        assertEquals("20260901", linePreview.statisticsKey());
        assertEquals(5, linePreview.totalRows());
        assertEquals(0, linePreview.errorRows());
        assertEquals(5, linePreview.warningRows());
        assertArrayEquals(arrears, store.retrieve(arrearsPreview.fileSha256().substring(0, 2) + "/" + arrearsPreview.fileSha256()));
        assertArrayEquals(lines, store.retrieve(linePreview.fileSha256().substring(0, 2) + "/" + linePreview.fileSha256()));
        verify(jdbc, times(8)).update(contains("INSERT INTO excel_import_row"), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(jdbc, never()).update(contains("INSERT INTO excel_snapshot_record"), any(Object[].class));
    }

    @Test
    void firstUploadForPeriodClassifiesEveryValidIdentityAsAdded() throws Exception {
        byte[] bytes = arrearsWorkbook(new String[]{"A-1", "10"}, new String[]{"B-1", "20"});
        activeBytes = bytes;
        LedgerWorkbookParser.ParseResult parsed = new LedgerWorkbookParser().parse(bytes,
                LedgerWorkbookParser.SourceType.ARREARS);
        when(jdbc.queryForObject(contains("batch_status NOT IN ('CANCELLED', 'FAILED')"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForObject(contains("batch_status='PRECHECKED'"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        doReturn(candidates(parsed, 11L, 12L)).when(jdbc)
                .query(contains("FROM excel_import_row"), any(RowMapper.class), eq(101L));

        LedgerImportService.UploadPreview preview = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "first-period.xlsx", bytes, "admin");

        assertEquals("PRECHECKED", preview.status());
        assertFalse(preview.duplicate());
        assertEquals(2, preview.revision().addedRows());
        assertEquals(0, preview.revision().changedRows());
        assertEquals(0, preview.revision().unchangedRows());
        assertEquals(0, preview.revision().retainedRows());
    }

    @Test
    void semanticNoOpAgainstCurrentDatasetIsMarkedDuplicate() throws Exception {
        byte[] bytes = arrearsWorkbook(new String[]{"A-1", "10"});
        activeBytes = bytes;
        LedgerWorkbookParser.ParseResult parsed = new LedgerWorkbookParser().parse(bytes,
                LedgerWorkbookParser.SourceType.ARREARS);
        when(jdbc.queryForObject(contains("batch_status NOT IN ('CANCELLED', 'FAILED')"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForObject(contains("batch_status='PRECHECKED'"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForList(contains("FROM excel_dataset_period p"), eq("ARREARS"), eq("202609")))
                .thenReturn(List.of(Map.of("current_version_id", 900L, "source_batch_id", 50L)));
        doReturn(candidates(parsed, 11L)).when(jdbc)
                .query(contains("FROM excel_import_row"), any(RowMapper.class), eq(101L));
        doReturn(currents(parsed, 21L)).when(jdbc)
                .query(contains("FROM excel_dataset_period p"), any(RowMapper.class), eq("ARREARS"), eq("202609"));

        LedgerImportService.UploadPreview preview = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "no-op.xlsx", bytes, "admin");

        assertTrue(preview.duplicate());
        assertEquals("DUPLICATE", preview.status());
        assertEquals(0, preview.revision().addedRows());
        assertEquals(0, preview.revision().changedRows());
        assertEquals(1, preview.revision().unchangedRows());
    }

    @Test
    void previouslySeenFileCanPreviewChangesAgainstCurrentDataset() throws Exception {
        byte[] previouslyConfirmed = arrearsWorkbook(new String[]{"A-1", "10"});
        byte[] current = arrearsWorkbook(new String[]{"A-1", "99"});
        activeBytes = previouslyConfirmed;
        LedgerWorkbookParser.ParseResult candidateParsed = new LedgerWorkbookParser().parse(previouslyConfirmed,
                LedgerWorkbookParser.SourceType.ARREARS);
        LedgerWorkbookParser.ParseResult currentParsed = new LedgerWorkbookParser().parse(current,
                LedgerWorkbookParser.SourceType.ARREARS);
        when(jdbc.queryForObject(contains("batch_status NOT IN ('CANCELLED', 'FAILED')"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(1);
        when(jdbc.queryForObject(contains("batch_status='PRECHECKED'"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForList(contains("FROM excel_dataset_period p"), eq("ARREARS"), eq("202609")))
                .thenReturn(List.of(Map.of("current_version_id", 900L, "source_batch_id", 50L)));
        doReturn(candidates(candidateParsed, 11L)).when(jdbc)
                .query(contains("FROM excel_import_row"), any(RowMapper.class), eq(101L));
        doReturn(currents(currentParsed, 21L)).when(jdbc)
                .query(contains("FROM excel_dataset_period p"), any(RowMapper.class), eq("ARREARS"), eq("202609"));

        LedgerImportService.UploadPreview preview = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "previously-confirmed.xlsx", previouslyConfirmed, "admin");

        assertTrue(preview.revision().previousBatchId() == 50L);
        assertEquals(1, preview.revision().changedRows());
        assertFalse(preview.duplicate());
        assertEquals("PRECHECKED", preview.status());
    }

    @Test
    void unreadableWorkbookIsRetainedAsBlockedAttempt() throws Exception {
        activeBytes = new byte[]{1, 2, 3, 4};
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM excel_import_batch"), eq(Integer.class),
                eq("LINE_EXPIRY"), eq(21L), eq(101L))).thenReturn(0);

        LedgerImportService.UploadPreview result = service.precheck(
                LedgerWorkbookParser.SourceType.LINE_EXPIRY, "broken.xlsx", activeBytes, "admin");

        assertEquals("BLOCKED", result.status());
        assertEquals(0, result.totalRows());
        assertEquals(1, result.errorRows());
        assertFalse(result.issues().isEmpty());
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(activeBytes));
        LedgerOriginalWorkbookStore.StoredWorkbook stored = store.store(activeBytes);
        when(jdbc.queryForList(contains("FROM excel_import_batch b"), eq(result.batchId())))
                .thenReturn(List.of(Map.of("original_filename", "broken.xlsx",
                        "storage_key", stored.storageKey(), "sha256", hash)));
        assertArrayEquals(activeBytes, service.originalWorkbook(result.batchId()).bytes());
        verify(jdbc).update(contains("INSERT INTO excel_import_batch"), any(), any(), any(), any());
        assertTrue(mockingDetails(jdbc).getInvocations().stream().anyMatch(invocation ->
                invocation.getMethod().getName().equals("update")
                        && ((String) invocation.getArgument(0)).contains("UPDATE excel_import_batch")));
        verify(jdbc, never()).update(contains("INSERT INTO excel_import_row"), any(Object[].class));
    }

    @Test
    void permanentlyDestroyedOriginalWorkbookReturnsGoneState() throws Exception {
        Map<String, Object> destroyed = new java.util.HashMap<>();
        destroyed.put("original_filename", "removed.xlsx");
        destroyed.put("storage_key", null);
        destroyed.put("sha256", null);
        when(jdbc.queryForList(contains("FROM excel_import_batch b"), eq(777L)))
                .thenReturn(List.of(destroyed));

        var exception = assertThrows(OriginalWorkbookDestroyedException.class,
                () -> service.originalWorkbook(777L));

        assertEquals(org.springframework.http.HttpStatus.GONE,
                exception.getClass().getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class).value());
        assertFalse(exception.getMessage().contains("storage_key"));
    }

    @Test
    void revisionPreviewClassifiesAddedUpdatedUnchangedAndRetainedRows() throws Exception {
        byte[] oldBytes = arrearsWorkbook(new String[]{"A-1", "1"}, new String[]{"B-1", "2"},
                new String[]{"C-1", "3"});
        byte[] newBytes = arrearsWorkbook(new String[]{"A-1", "1"}, new String[]{"B-1", "20"},
                new String[]{"D-1", "4"});
        activeBytes = newBytes;
        LedgerWorkbookParser.ParseResult oldParsed = new LedgerWorkbookParser().parse(oldBytes,
                LedgerWorkbookParser.SourceType.ARREARS);
        LedgerWorkbookParser.ParseResult newParsed = new LedgerWorkbookParser().parse(newBytes,
                LedgerWorkbookParser.SourceType.ARREARS);
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM excel_import_batch"), eq(Integer.class),
                eq("ARREARS"), eq(21L), eq(101L))).thenReturn(0);
        when(jdbc.queryForList(contains("FROM excel_dataset_period p"), eq("ARREARS"), eq("202609")))
                .thenReturn(List.of(Map.of("current_version_id", 900L, "source_batch_id", 50L)));
        doReturn(candidates(newParsed, 11L, 12L, 13L)).when(jdbc)
                .query(contains("FROM excel_import_row"), any(RowMapper.class), eq(101L));
        doReturn(currents(oldParsed, 1L, 2L, 3L)).when(jdbc)
                .query(contains("FROM excel_dataset_period p"), any(RowMapper.class), eq("ARREARS"), eq("202609"));

        LedgerImportService.UploadPreview preview = service.precheck(
                LedgerWorkbookParser.SourceType.ARREARS, "revision.xlsx", newBytes, "admin");

        assertEquals(50L, preview.revision().previousBatchId());
        assertEquals(1, preview.revision().addedRows());
        assertEquals(0, preview.revision().removedRows());
        assertEquals(1, preview.revision().changedRows());
        assertEquals(1, preview.revision().unchangedRows());
        assertEquals(1, preview.revision().retainedRows());
        assertEquals(1, preview.revision().differences().stream().filter(diff -> "RETAINED".equals(diff.changeType())).count());
        assertEquals(1, preview.revision().differences().stream().filter(diff -> "ADDED".equals(diff.changeType())).count());
        assertEquals(1, preview.revision().differences().stream().filter(diff -> "UPDATED".equals(diff.changeType())).count());
        assertEquals(1, preview.revision().differences().stream().filter(diff -> "UNCHANGED".equals(diff.changeType())).count());
        assertEquals("PRECHECKED", preview.status());
        verify(jdbc, times(3)).update(contains("INSERT INTO excel_revision_diff"),
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(jdbc).update(contains("VALUES (?, ?, 'RETAINED', ?, NULL, ?, ?, ?)"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void fileStoreVerifiesContentAndRejectsInvalidStorageKeys() throws Exception {
        byte[] bytes = arrearsWorkbook();
        LedgerOriginalWorkbookStore.StoredWorkbook stored = store.store(bytes);

        assertArrayEquals(bytes, store.retrieve(stored.storageKey()));
        assertEquals(bytes.length, stored.byteSize());
        assertEquals(stored, store.store(bytes));
        assertThrows(java.io.IOException.class, () -> store.retrieve("../secret"));
    }

    private byte[] arrearsWorkbook() throws Exception {
        return arrearsWorkbook(new String[]{"A-1", "0"});
    }

    private byte[] supplied(String fileName) throws Exception {
        return java.nio.file.Files.readAllBytes(java.nio.file.Path.of("..", "excel", fileName).toAbsolutePath().normalize());
    }

    private byte[] arrearsWorkbook(String[]... records) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            String[] headers = {"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "期末欠费金额"};
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) {
                header.createCell(index).setCellValue(headers[index]);
            }
            for (int rowIndex = 0; rowIndex < records.length; rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                String[] values = {"202609", "区县一", "G-1", "集团一", "G-1", "张伟", records[rowIndex][0], records[rowIndex][1]};
                for (int index = 0; index < values.length; index++) {
                    row.createCell(index).setCellValue(values[index]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private List<LedgerMergePlanner.Candidate> candidates(LedgerWorkbookParser.ParseResult parsed, Long... ids) {
        List<LedgerMergePlanner.Candidate> rows = new ArrayList<>();
        for (int index = 0; index < parsed.rows().size(); index++) {
            var row = parsed.rows().get(index);
            rows.add(new LedgerMergePlanner.Candidate(ids[index], row.identityKey(), row.sheetName(),
                    row.rowNumber(), new ObjectMapper().valueToTree(row.normalizedFields()),
                    new ObjectMapper().valueToTree(row.rawFields())));
        }
        return rows;
    }

    private List<LedgerMergePlanner.Current> currents(LedgerWorkbookParser.ParseResult parsed, Long... ids) {
        List<LedgerMergePlanner.Current> rows = new ArrayList<>();
        for (int index = 0; index < parsed.rows().size(); index++) {
            var row = parsed.rows().get(index);
            rows.add(new LedgerMergePlanner.Current(row.identityKey(), ids[index], 50L, ids[index] + 100,
                    ids[index] + 200, row.sheetName(), row.rowNumber(), row.excelManagerName(), null,
                    new ObjectMapper().valueToTree(row.normalizedFields()),
                    new ObjectMapper().valueToTree(row.rawFields()), new ObjectMapper().createObjectNode()));
        }
        return rows;
    }
}
