package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Destructive-path checks enabled only by the disposable-MySQL integration runner. */
@EnabledIfEnvironmentVariable(named = "LEDGER_ERASURE_MYSQL_TESTS", matches = "true")
@SpringBootTest
@AutoConfigureMockMvc
class LedgerRecordErasureMySqlIntegrationTests {
    private static final AtomicInteger TEST_YEAR = new AtomicInteger(3000);
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LedgerRecordErasurePreviewService previews;
    @Autowired private LedgerRecordErasureService erasures;
    @Autowired private LedgerOriginalWorkbookStore workbookStore;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    private TransactionTemplate transactions;
    private long adminId;

    @BeforeEach
    void prepare() {
        transactions = new TransactionTemplate(transactionManager);
        adminId = jdbc.queryForObject("SELECT id FROM app_user WHERE username='admin' AND role='ADMIN' AND enabled=TRUE", Long.class);
    }

    @Test
    void hardDeleteRemovesOnlySelectedIdentityHistoryAcrossBatchesAndVersionsForBothLedgers() {
        for (String sourceType : List.of("ARREARS", "LINE_EXPIRY")) {
            LedgerSeed seed = seedLedger(sourceType, null, false);
            var preview = previews.preview(seed.source(), List.of(seed.currentTarget().snapshotId()), "admin");
            assertEquals(2, preview.periods().get(0).historicalImportRows());
            assertEquals(2, preview.periods().get(0).snapshots());
            assertEquals(1, preview.periods().get(0).workOrders());
            assertEquals(1, preview.periods().get(0).workOrderStatusChanges());
            assertEquals(1, preview.periods().get(0).workOrderFollowUps());

            var result = erasures.erase(preview.previewToken(), "admin", false,
                    LedgerRecordErasureService.CONFIRMATION_TEXT);

            assertEquals(1, result.deletedRecords());
            assertEquals(2, result.deletedImportRows());
            assertEquals(2, result.affectedBatches());
            assertEquals("RETAINED", result.originalFileStatus());
            assertEquals(0, countTargetImportRows(seed));
            assertEquals(0, countTargetSnapshots(seed));
            assertEquals(0, countTargetCurrentRows(seed));
            assertEquals(1, countCurrentRows(seed, seed.otherIdentityKey()));
            assertEquals(1, countIdentityInPeriod(seed, seed.outsidePeriod(), seed.stableIdentity()));
            assertEquals(0, countWorkOrders(seed, seed.targetPeriod(), seed.stableIdentity()));
            assertEquals(1, countWorkOrders(seed, seed.targetPeriod(), seed.otherStableIdentity()));
            assertEquals(0, countRevisionDiffs(seed));
            assertEquals(0, countAssignmentRecords(seed));
            assertEquals(1, jdbc.queryForObject("SELECT erased_rows FROM excel_import_batch WHERE id=?", Integer.class,
                    seed.firstBatchId()));
            assertEquals(1, jdbc.queryForObject("SELECT erased_rows FROM excel_import_batch WHERE id=?", Integer.class,
                    seed.currentBatchId()));

            Map<String, Object> audit = jdbc.queryForMap("""
                    SELECT operation_type, target_type, target_id, result, detail
                    FROM operation_audit_log WHERE id=?
                    """, result.auditRecordId());
            assertEquals("LEDGER_RECORD_ERASURE", audit.get("operation_type"));
            assertEquals(sourceType, audit.get("target_type"));
            assertEquals(null, audit.get("target_id"));
            assertFalse(String.valueOf(audit.get("detail")).contains(seed.stableIdentity()));
            assertFalse(String.valueOf(audit.get("detail")).contains(seed.groupName()));
            assertFalse(String.valueOf(audit.get("detail")).contains(seed.filename()));
            assertThrows(IllegalArgumentException.class, () -> erasures.erase(preview.previewToken(), "admin", false,
                    LedgerRecordErasureService.CONFIRMATION_TEXT));
        }
    }

    @Test
    void sharedOriginalWorkbookCanBeDestroyedWithoutDeletingOtherLedgerRows() throws Exception {
        byte[] bytes = ("shared workbook fixture " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        LedgerOriginalWorkbookStore.StoredWorkbook stored = workbookStore.store(bytes);
        Long fileId = transactions.execute(status -> {
            jdbc.update("INSERT INTO excel_source_file(sha256,storage_key,byte_size) VALUES (?,?,?)",
                    stored.sha256(), stored.storageKey(), stored.byteSize());
            return jdbc.queryForObject("SELECT id FROM excel_source_file WHERE sha256=?", Long.class, stored.sha256());
        });

        LedgerSeed arrears = seedLedger("ARREARS", fileId, true);
        LedgerSeed lines = seedLedger("LINE_EXPIRY", fileId, false);
        var preview = previews.preview(arrears.source(), List.of(arrears.currentTarget().snapshotId()), "admin");
        assertEquals(3, preview.originalFiles().get(0).referenceBatches());
        assertEquals(3, preview.originalFiles().get(0).affectedPeriods());
        assertEquals(5, preview.originalFiles().get(0).parsedBusinessKeys());
        assertEquals(4, preview.originalFiles().get(0).otherBusinessKeys());

        var result = erasures.erase(preview.previewToken(), "admin", true,
                LedgerRecordErasureService.CONFIRMATION_TEXT);
        assertEquals("PENDING", result.originalFileStatus());
        assertEquals(1, result.filesQueued());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_source_file WHERE id=?", Integer.class, fileId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch WHERE source_file_id=?", Integer.class, fileId));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch WHERE original_file_purge_status='PENDING' AND original_file_purge_operation_id=?",
                Integer.class, result.operationId()));
        assertEquals(1, countIdentityInPeriod(arrears, arrears.outsidePeriod(), arrears.stableIdentity()));
        assertEquals(1, countCurrentRows(arrears, arrears.otherIdentityKey()));
        assertEquals(1, countCurrentRows(lines, lines.otherIdentityKey()));

        var cleanup = erasures.processPhysicalCleanup(result);
        assertEquals("PURGED", cleanup.originalFileStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM excel_original_file_purge_queue WHERE operation_id=?",
                Integer.class, result.operationId()));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch WHERE original_file_purge_status='PURGED' AND original_file_purge_operation_id=?",
                Integer.class, result.operationId()));
        assertFalse(java.nio.file.Files.exists(java.nio.file.Path.of(System.getenv("LEDGER_WORKBOOK_DIRECTORY"), stored.storageKey())));
    }

    @Test
    void previewAndConfirmApisEnforceAdminValidationAndStaleVersionRejection() throws Exception {
        LedgerSeed seed = seedLedger("ARREARS", null, false);
        String encoded = objectMapper.writeValueAsString(Map.of(
                "sourceType", "ARREARS", "snapshotRecordIds", List.of(seed.currentTarget().snapshotId())));
        mvc.perform(post("/api/ledger-erasure/preview").with(user("huangling").roles("CUSTOMER_MANAGER"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(encoded))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/ledger-erasure/preview").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceType\":\"ARREARS\",\"snapshotRecordIds\":[]}"))
                .andExpect(status().isBadRequest());

        var preview = previews.preview(seed.source(), List.of(seed.currentTarget().snapshotId()), "admin");
        jdbc.update("UPDATE excel_dataset_period SET current_version_id=? WHERE source_type=? AND statistics_key=?",
                seed.firstVersionId(), seed.sourceType(), seed.targetPeriod());
        String confirm = objectMapper.writeValueAsString(Map.of(
                "previewToken", preview.previewToken(), "deleteOriginalWorkbooks", false,
                "confirmationText", LedgerRecordErasureService.CONFIRMATION_TEXT));
        mvc.perform(post("/api/ledger-erasure/confirm").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERASURE_PREVIEW_STALE"));
        assertEquals(2, countTargetImportRows(seed));
        assertEquals(2, countTargetSnapshots(seed));

        jdbc.update("UPDATE excel_dataset_period SET current_version_id=? WHERE source_type=? AND statistics_key=?",
                seed.currentVersionId(), seed.sourceType(), seed.targetPeriod());
        MvcResult freshPreview = mvc.perform(post("/api/ledger-erasure/preview").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(encoded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periods[0].historicalImportRows").value(2))
                .andExpect(jsonPath("$.data.periods[0].workOrders").value(1))
                .andReturn();
        String freshToken = objectMapper.readTree(freshPreview.getResponse().getContentAsByteArray())
                .path("data").path("previewToken").asText();
        String validConfirm = objectMapper.writeValueAsString(Map.of(
                "previewToken", freshToken, "deleteOriginalWorkbooks", false,
                "confirmationText", LedgerRecordErasureService.CONFIRMATION_TEXT));
        mvc.perform(post("/api/ledger-erasure/confirm").with(user("admin").roles("ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(validConfirm))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedRecords").value(1))
                .andExpect(jsonPath("$.data.deletedImportRows").value(2))
                .andExpect(jsonPath("$.data.originalFileStatus").value("RETAINED"));
        assertEquals(0, countTargetImportRows(seed));

        assertThrows(IllegalArgumentException.class, () -> previews.preview(seed.source(), List.of(-1L), "admin"));
    }

    @Test
    void auditFailureRollsBackAllDatabaseDeletionWork() throws Exception {
        LedgerSeed seed = seedLedger("LINE_EXPIRY", null, false);
        var preview = previews.preview(seed.source(), List.of(seed.currentTarget().snapshotId()), "admin");
        String trigger = "test_erasure_audit_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON operation_audit_log FOR EACH ROW "
                + "BEGIN IF NEW.operation_type='LEDGER_RECORD_ERASURE' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced erasure audit failure'; END IF; END");
        try {
            assertThrows(DataAccessException.class, () -> erasures.erase(preview.previewToken(), "admin", false,
                    LedgerRecordErasureService.CONFIRMATION_TEXT));
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger);
        }
        assertEquals(2, countTargetImportRows(seed));
        assertEquals(2, countTargetSnapshots(seed));
        assertEquals(1, countTargetCurrentRows(seed));
        assertEquals(1, countWorkOrders(seed, seed.targetPeriod(), seed.stableIdentity()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE operation_type='LEDGER_RECORD_ERASURE' AND detail LIKE ?",
                Integer.class, "%" + seed.targetPeriod() + "%"));
    }

    private LedgerSeed seedLedger(String sourceType, Long sharedFileId, boolean arrearsWithSharedSecondPeriod) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        int year = TEST_YEAR.getAndIncrement();
        String targetPeriod = sourceType.equals("ARREARS") ? String.format("%04d11", year)
                : String.format("%04d1111", year);
        String outsidePeriod = sourceType.equals("ARREARS") ? String.format("%04d10", year - 1)
                : String.format("%04d1010", year - 1);
        String stable = (sourceType.equals("ARREARS") ? "acct-secret-" : "svc-secret-") + suffix;
        String otherStable = (sourceType.equals("ARREARS") ? "acct-other-" : "svc-other-") + suffix;
        String groupName = "private-group-" + suffix;
        String filename = "private-workbook-" + suffix + ".xlsx";

        return transactions.execute(status -> {
            long firstBatch = createBatch(sourceType, targetPeriod, filename + "-v1", null);
            Snapshot firstTarget = createSnapshot(firstBatch, sourceType, targetPeriod, stable, groupName, 2);
            long currentBatch = createBatch(sourceType, targetPeriod, filename, sharedFileId);
            Snapshot currentTarget = createSnapshot(currentBatch, sourceType, targetPeriod, stable, groupName, 2);
            Snapshot other = createSnapshot(currentBatch, sourceType, targetPeriod, otherStable, "other-group-" + suffix, 3);
            long firstVersion = createVersion(sourceType, targetPeriod, firstBatch, null);
            long currentVersion = createVersion(sourceType, targetPeriod, currentBatch, firstVersion);
            addEffective(firstTarget, firstBatch, firstVersion, sourceType, targetPeriod, stable);
            addEffective(currentTarget, currentBatch, currentVersion, sourceType, targetPeriod, stable);
            addEffective(other, currentBatch, currentVersion, sourceType, targetPeriod, otherStable);
            createDatasetPeriod(sourceType, targetPeriod, currentVersion);
            createRevisionDiff(currentBatch, stable, firstTarget.importRowId(), currentTarget.importRowId());
            addAssignmentRecords(currentTarget.snapshotId());
            addWorkOrder(sourceType, targetPeriod, stable, currentTarget.snapshotId());
            addWorkOrder(sourceType, targetPeriod, otherStable, other.snapshotId());

            Long outsideFileId = arrearsWithSharedSecondPeriod && sourceType.equals("ARREARS") ? sharedFileId : null;
            long outsideBatch = createBatch(sourceType, outsidePeriod, filename + "-other-period", outsideFileId);
            Snapshot outsideTarget = createSnapshot(outsideBatch, sourceType, outsidePeriod, stable, groupName, 2);
            long outsideVersion = createVersion(sourceType, outsidePeriod, outsideBatch, null);
            addEffective(outsideTarget, outsideBatch, outsideVersion, sourceType, outsidePeriod, stable);
            createDatasetPeriod(sourceType, outsidePeriod, outsideVersion);
            return new LedgerSeed(sourceType, targetPeriod, outsidePeriod, stable, otherStable, groupName, filename,
                    firstBatch, currentBatch, firstVersion, currentVersion, firstTarget, currentTarget, other,
                    outsideBatch, outsideTarget, outsideVersion);
        });
    }

    private long createBatch(String sourceType, String period, String filename, Long sourceFileId) {
        jdbc.update("""
                INSERT INTO excel_import_batch(source_type,statistics_key,original_filename,source_file_id,
                    uploaded_by_user_id,batch_status,total_rows,valid_rows)
                VALUES (?,?,?,?,?,'CONFIRMED',1,1)
                """, sourceType, period, filename, sourceFileId, adminId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Snapshot createSnapshot(long batchId, String sourceType, String period, String stable,
                                   String groupName, int rowNumber) {
        String identityKey = period + ":" + stable;
        jdbc.update("""
                INSERT INTO excel_import_row(batch_id,sheet_name,source_row_number,identity_key,
                    validation_status,raw_fields,normalized_fields)
                VALUES (?,'Data',? ,?,'VALID',?,?)
                """, batchId, rowNumber, identityKey, "[]", "{}");
        long importRowId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO excel_snapshot_record(batch_id,source_type,import_row_id,excel_manager_name)
                VALUES (?,?,?,'原始经理')
                """, batchId, sourceType, importRowId);
        long snapshotId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (sourceType.equals("ARREARS")) {
            jdbc.update("""
                    INSERT INTO arrears_snapshot(snapshot_record_id,batch_id,source_type,account_id,group_code,
                        group_name,carrying_product_name,ending_arrears_amount,aging_value)
                    VALUES (?,?,'ARREARS',?,?,?,'承载产品',1200.00,'30天')
                    """, snapshotId, batchId, stable, "group-code-" + stable, groupName);
        } else {
            jdbc.update("""
                    INSERT INTO line_expiry_snapshot(snapshot_record_id,batch_id,source_type,member_service_number,
                        group_code,group_name,product_code,product_name,billing_cutoff_date)
                    VALUES (?,?,'LINE_EXPIRY',?,?,?,'product-code','line-product','2027-01-31')
                    """, snapshotId, batchId, stable, "group-code-" + stable, groupName);
        }
        return new Snapshot(importRowId, snapshotId, identityKey);
    }

    private long createVersion(String sourceType, String period, long sourceBatchId, Long basedOn) {
        jdbc.update("""
                INSERT INTO excel_dataset_version(source_type,statistics_key,source_batch_id,based_on_version_id,
                    created_by_user_id,added_rows)
                VALUES (?,?,?,?,?,1)
                """, sourceType, period, sourceBatchId, basedOn, adminId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void addEffective(Snapshot snapshot, long batchId, long versionId, String sourceType,
                              String period, String stable) {
        jdbc.update("""
                INSERT INTO excel_effective_row(source_type,statistics_key,identity_key,latest_batch_id,
                    snapshot_record_id,raw_fields,normalized_fields,field_provenance)
                VALUES (?,?,?,?,?,'[]','{}','{}')
                """, sourceType, period, snapshot.identityKey(), batchId, snapshot.snapshotId());
        long effectiveId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO excel_dataset_version_row(dataset_version_id,source_type,statistics_key,identity_key,effective_row_id)
                VALUES (?,?,?,?,?)
                """, versionId, sourceType, period, snapshot.identityKey(), effectiveId);
    }

    private void createDatasetPeriod(String sourceType, String period, long versionId) {
        jdbc.update("""
                INSERT INTO excel_dataset_period(source_type,statistics_key,current_version_id,changed_by_user_id)
                VALUES (?,?,?,?)
                """, sourceType, period, versionId, adminId);
    }

    private void createRevisionDiff(long batchId, String stable, long previousRowId, long candidateRowId) {
        String period = jdbc.queryForObject("SELECT statistics_key FROM excel_import_batch WHERE id=?", String.class, batchId);
        jdbc.update("""
                INSERT INTO excel_revision_diff(batch_id,identity_key,change_type,previous_import_row_id,candidate_import_row_id)
                VALUES (?,?, 'UPDATED', ?, ?)
                """, batchId, period + ":" + stable, previousRowId, candidateRowId);
    }

    private void addAssignmentRecords(long snapshotId) {
        jdbc.update("""
                INSERT INTO assignment_exception(snapshot_record_id,exception_type,reason,status)
                VALUES (?,'NO_MATCH','manager unavailable','OPEN')
                """, snapshotId);
        jdbc.update("""
                INSERT INTO assignment_history(snapshot_record_id,new_manager_user_id,change_type,changed_by_user_id,reason)
                VALUES (?,?, 'MANUAL_ASSIGNMENT', ?, 'test assignment')
                """, snapshotId, adminId, adminId);
    }

    private void addWorkOrder(String sourceType, String period, String stable, long snapshotId) {
        String table = sourceType.equals("ARREARS") ? "arrears_work_order" : "line_renewal_work_order";
        String identityColumn = sourceType.equals("ARREARS") ? "account_id" : "member_service_number";
        String history = sourceType.equals("ARREARS") ? "arrears_work_order_status_history" : "line_renewal_status_history";
        String followUps = sourceType.equals("ARREARS") ? "arrears_work_order_follow_up" : "line_renewal_work_order_follow_up";
        String status = sourceType.equals("ARREARS") ? "跟进中" : "不续签";
        jdbc.update("INSERT INTO " + table + "(statistics_key," + identityColumn + ",current_snapshot_record_id,status) VALUES (?,?,?,?)",
                period, stable, snapshotId, status);
        long orderId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("INSERT INTO " + history + "(work_order_id,previous_status,new_status,changed_by_user_id) VALUES (?,?,?,?)",
                orderId, "待处理", status, adminId);
        jdbc.update("INSERT INTO " + followUps + "(work_order_id,note,created_by_user_id) VALUES (?,?,?)",
                orderId, "sensitive follow-up for " + stable, adminId);
    }

    private int countTargetImportRows(LedgerSeed seed) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_import_row r JOIN excel_import_batch b ON b.id=r.batch_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key=?
                """, Integer.class, seed.sourceType(), seed.targetPeriod(), seed.targetIdentityKey());
    }

    private int countTargetSnapshots(LedgerSeed seed) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM excel_snapshot_record s JOIN excel_import_row r ON r.id=s.import_row_id
                JOIN excel_import_batch b ON b.id=s.batch_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key=?
                """, Integer.class, seed.sourceType(), seed.targetPeriod(), seed.targetIdentityKey());
    }

    private int countTargetCurrentRows(LedgerSeed seed) {
        return countCurrentRows(seed, seed.targetIdentityKey());
    }

    private int countCurrentRows(LedgerSeed seed, String identityKey) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM excel_current_dataset WHERE source_type=? AND statistics_key=? AND identity_key=?",
                Integer.class, seed.sourceType(), seed.targetPeriod(), identityKey);
    }

    private int countIdentityInPeriod(LedgerSeed seed, String period, String stableIdentity) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_row r JOIN excel_import_batch b ON b.id=r.batch_id "
                        + "WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key=?",
                Integer.class, seed.sourceType(), period, period + ":" + stableIdentity);
    }

    private int countWorkOrders(LedgerSeed seed, String period, String stableIdentity) {
        String table = seed.sourceType().equals("ARREARS") ? "arrears_work_order" : "line_renewal_work_order";
        String identityColumn = seed.sourceType().equals("ARREARS") ? "account_id" : "member_service_number";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE statistics_key=? AND " + identityColumn + "=?",
                Integer.class, period, stableIdentity);
    }

    private int countRevisionDiffs(LedgerSeed seed) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM excel_revision_diff d JOIN excel_import_batch b ON b.id=d.batch_id "
                + "WHERE b.source_type=? AND b.statistics_key=? AND d.identity_key=?",
                Integer.class, seed.sourceType(), seed.targetPeriod(), seed.targetIdentityKey());
    }

    private int countAssignmentRecords(LedgerSeed seed) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM assignment_history h JOIN excel_snapshot_record s ON s.id=h.snapshot_record_id "
                        + "JOIN excel_import_row r ON r.id=s.import_row_id JOIN excel_import_batch b ON b.id=s.batch_id "
                        + "WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key=?",
                Integer.class, seed.sourceType(), seed.targetPeriod(), seed.targetIdentityKey());
    }

    private record Snapshot(long importRowId, long snapshotId, String identityKey) {}

    private record LedgerSeed(String sourceType, String targetPeriod, String outsidePeriod, String stableIdentity,
                              String otherStableIdentity, String groupName, String filename, long firstBatchId,
                              long currentBatchId, long firstVersionId, long currentVersionId, Snapshot firstTarget,
                              Snapshot currentTarget, Snapshot other, long outsideBatchId, Snapshot outsideTarget,
                              long outsideVersionId) {
        LedgerWorkbookParser.SourceType source() {
            return LedgerWorkbookParser.SourceType.valueOf(sourceType);
        }
        String targetIdentityKey() { return targetPeriod + ":" + stableIdentity; }
        String otherIdentityKey() { return targetPeriod + ":" + otherStableIdentity; }
    }
}
