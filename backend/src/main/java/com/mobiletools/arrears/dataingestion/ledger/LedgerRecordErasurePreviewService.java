package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves selected current snapshots to their complete period-scoped deletion impact. */
@Service
public class LedgerRecordErasurePreviewService {
    private static final int MAX_IDENTITIES = 100;
    private final JdbcTemplate jdbc;
    private final LedgerErasurePreviewRegistry registry;

    public LedgerRecordErasurePreviewService(JdbcTemplate jdbc, LedgerErasurePreviewRegistry registry) {
        this.jdbc = jdbc;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public PreviewResult preview(LedgerWorkbookParser.SourceType sourceType, List<Long> snapshotRecordIds,
                                 String operator) {
        if (sourceType == null) throw new IllegalArgumentException("必须指定数据来源");
        if (operator == null || operator.isBlank()) throw new IllegalArgumentException("管理员身份无效");
        if (snapshotRecordIds == null || snapshotRecordIds.isEmpty()) {
            throw new IllegalArgumentException("至少选择一条当前记录");
        }
        if (snapshotRecordIds.size() > MAX_IDENTITIES) {
            throw new IllegalArgumentException("一次最多永久删除 100 条记录，请拆分操作");
        }
        if (snapshotRecordIds.stream().anyMatch(id -> id == null || id <= 0)
                || new LinkedHashSet<>(snapshotRecordIds).size() != snapshotRecordIds.size()) {
            throw new IllegalArgumentException("所选记录无效或重复");
        }

        String placeholders = String.join(",", snapshotRecordIds.stream().map(id -> "?").toList());
        String query = selectedSnapshotQuery(sourceType) + " WHERE cv.source_type=? AND s.id IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>();
        args.add(sourceType.name());
        args.addAll(snapshotRecordIds);
        List<SelectedRow> selected = jdbc.query(query, (rs, row) -> mapSelected(rs), args.toArray());
        if (selected.size() != snapshotRecordIds.size()) {
            throw new IllegalArgumentException("所选记录已不在当前数据中，请刷新后重新选择");
        }

        selected.sort(Comparator.comparing(SelectedRow::statisticsKey).thenComparing(SelectedRow::identityKey));
        Map<String, Long> versions = new LinkedHashMap<>();
        for (SelectedRow row : selected) {
            Long previous = versions.putIfAbsent(row.statisticsKey(), row.currentVersionId());
            if (previous != null && !previous.equals(row.currentVersionId())) {
                throw new IllegalStateException("统计期当前版本读取不一致，请重新预览");
            }
        }

        List<PeriodImpact> periods = selected.stream().collect(Collectors.groupingBy(
                        SelectedRow::statisticsKey, LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(entry -> periodImpact(sourceType, entry.getKey(), entry.getValue()))
                .toList();
        List<LedgerErasurePreviewRegistry.FileReference> files = findOriginalFiles(sourceType, selected);
        LedgerErasurePreviewRegistry.Plan plan = new LedgerErasurePreviewRegistry.Plan(sourceType.name(),
                selected.stream().map(row -> new LedgerErasurePreviewRegistry.TargetIdentity(
                        row.snapshotRecordId(), row.statisticsKey(), row.identityKey(), row.stableIdentity(),
                        row.groupCode(), row.groupName())).toList(), versions, files);
        LedgerErasurePreviewRegistry.IssuedPreview issued = registry.issue(operator, plan);
        return new PreviewResult(issued.token(), issued.expiresAt(), sourceType.name(),
                selected.stream().map(row -> new SelectedRecord(row.snapshotRecordId(), row.statisticsKey(),
                        row.stableIdentity(), row.groupCode(), row.groupName())).toList(), periods,
                files.stream().map(file -> fileImpact(file, selected)).toList(), MAX_IDENTITIES);
    }

    private PeriodImpact periodImpact(LedgerWorkbookParser.SourceType sourceType, String period,
                                      List<SelectedRow> rows) {
        List<String> identityKeys = rows.stream().map(SelectedRow::identityKey).toList();
        List<String> stableIds = rows.stream().map(SelectedRow::stableIdentity).toList();
        String rowMarks = marks(identityKeys.size());
        String idsMarks = marks(stableIds.size());
        List<Object> scopedArgs = new ArrayList<>();
        scopedArgs.add(sourceType.name()); scopedArgs.add(period); scopedArgs.addAll(identityKeys);

        long importRows = count("""
                SELECT COUNT(*) FROM excel_import_row r JOIN excel_import_batch b ON b.id=r.batch_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long batchCount = count("""
                SELECT COUNT(DISTINCT b.id) FROM excel_import_row r JOIN excel_import_batch b ON b.id=r.batch_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long versions = count("""
                SELECT COUNT(DISTINCT v.id) FROM excel_dataset_version_row m
                JOIN excel_dataset_version v ON v.id=m.dataset_version_id
                WHERE v.source_type=? AND v.statistics_key=? AND m.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long revisionDiffs = count("""
                SELECT COUNT(*) FROM excel_revision_diff d JOIN excel_import_batch b ON b.id=d.batch_id
                WHERE b.source_type=? AND b.statistics_key=? AND d.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long snapshots = count("""
                SELECT COUNT(*) FROM excel_snapshot_record s JOIN excel_import_batch b ON b.id=s.batch_id
                JOIN excel_import_row r ON r.id=s.import_row_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long assignmentExceptions = count("""
                SELECT COUNT(*) FROM assignment_exception e JOIN excel_snapshot_record s ON s.id=e.snapshot_record_id
                JOIN excel_import_batch b ON b.id=s.batch_id JOIN excel_import_row r ON r.id=s.import_row_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());
        long assignmentHistory = count("""
                SELECT COUNT(*) FROM assignment_history h JOIN excel_snapshot_record s ON s.id=h.snapshot_record_id
                JOIN excel_import_batch b ON b.id=s.batch_id JOIN excel_import_row r ON r.id=s.import_row_id
                WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key IN (%s)
                """.formatted(rowMarks), scopedArgs.toArray());

        WorkOrderImpact orders = sourceType == LedgerWorkbookParser.SourceType.ARREARS
                ? arrearsWorkOrders(period, idsMarks, stableIds)
                : lineWorkOrders(period, idsMarks, stableIds);
        return new PeriodImpact(period, rows.size(), importRows, snapshots, batchCount, versions,
                revisionDiffs, assignmentExceptions, assignmentHistory, orders.count(),
                orders.statusChanges(), orders.followUps());
    }

    private WorkOrderImpact arrearsWorkOrders(String period, String marks, List<String> ids) {
        List<Object> args = new ArrayList<>(); args.add(period); args.addAll(ids);
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id FROM arrears_work_order WHERE statistics_key=? AND account_id IN (%s)
                """.formatted(marks), args.toArray());
        List<Long> orderIds = orders.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        if (orderIds.isEmpty()) return new WorkOrderImpact(0, 0, 0);
        String orderMarks = marks(orderIds.size());
        Object[] orderArgs = orderIds.toArray();
        return new WorkOrderImpact(orderIds.size(), count("SELECT COUNT(*) FROM arrears_work_order_status_history WHERE work_order_id IN (" + orderMarks + ")", orderArgs),
                count("SELECT COUNT(*) FROM arrears_work_order_follow_up WHERE work_order_id IN (" + orderMarks + ")", orderArgs));
    }

    private WorkOrderImpact lineWorkOrders(String period, String marks, List<String> ids) {
        List<Object> args = new ArrayList<>(); args.add(period); args.addAll(ids);
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT id FROM line_renewal_work_order WHERE statistics_key=? AND member_service_number IN (%s)
                """.formatted(marks), args.toArray());
        List<Long> orderIds = orders.stream().map(row -> ((Number) row.get("id")).longValue()).toList();
        if (orderIds.isEmpty()) return new WorkOrderImpact(0, 0, 0);
        String orderMarks = marks(orderIds.size());
        Object[] orderArgs = orderIds.toArray();
        return new WorkOrderImpact(orderIds.size(), count("SELECT COUNT(*) FROM line_renewal_status_history WHERE work_order_id IN (" + orderMarks + ")", orderArgs),
                count("SELECT COUNT(*) FROM line_renewal_work_order_follow_up WHERE work_order_id IN (" + orderMarks + ")", orderArgs));
    }

    private List<LedgerErasurePreviewRegistry.FileReference> findOriginalFiles(
            LedgerWorkbookParser.SourceType sourceType, List<SelectedRow> rows) {
        Map<Long, FileAccumulator> files = new LinkedHashMap<>();
        for (SelectedRow row : rows) {
            List<Map<String, Object>> references = jdbc.queryForList("""
                    SELECT DISTINCT f.id AS file_id, f.storage_key, b.id AS batch_id, b.original_filename
                    FROM excel_import_batch b JOIN excel_source_file f ON f.id=b.source_file_id
                    JOIN excel_import_row r ON r.batch_id=b.id
                    WHERE b.source_type=? AND b.statistics_key=? AND r.identity_key=?
                    """, sourceType.name(), row.statisticsKey(), row.identityKey());
            for (Map<String, Object> reference : references) {
                long fileId = ((Number) reference.get("file_id")).longValue();
                FileAccumulator file = files.computeIfAbsent(fileId, ignored ->
                        new FileAccumulator(fileId, String.valueOf(reference.get("storage_key"))));
                file.batchIds.add(((Number) reference.get("batch_id")).longValue());
                file.filenames.add(String.valueOf(reference.get("original_filename")));
            }
        }
        return files.values().stream().map(file -> {
            List<Map<String, Object>> allReferences = jdbc.queryForList("""
                    SELECT id AS batch_id, original_filename FROM excel_import_batch WHERE source_file_id=?
                    ORDER BY id
                    """, file.id);
            file.batchIds.clear();
            file.filenames.clear();
            for (Map<String, Object> reference : allReferences) {
                file.batchIds.add(((Number) reference.get("batch_id")).longValue());
                file.filenames.add(String.valueOf(reference.get("original_filename")));
            }
            return new LedgerErasurePreviewRegistry.FileReference(file.id, file.storageKey,
                    new ArrayList<>(file.batchIds), new ArrayList<>(file.filenames));
        }).toList();
    }

    private FileImpact fileImpact(LedgerErasurePreviewRegistry.FileReference file, List<SelectedRow> selected) {
        List<Map<String, Object>> allKeys = jdbc.queryForList("""
                SELECT DISTINCT b.source_type, b.statistics_key, r.identity_key
                FROM excel_import_batch b JOIN excel_import_row r ON r.batch_id=b.id
                WHERE b.source_file_id=? AND r.identity_key IS NOT NULL
                """, file.sourceFileId());
        Set<String> selectedKeys = selected.stream()
                .map(row -> row.sourceType() + "|" + row.statisticsKey() + "|" + row.identityKey())
                .collect(Collectors.toSet());
        long otherKeys = allKeys.stream().map(row -> row.get("source_type") + "|" + row.get("statistics_key") + "|" + row.get("identity_key"))
                .filter(key -> !selectedKeys.contains(key)).distinct().count();
        Long batchCount = jdbc.queryForObject("SELECT COUNT(*) FROM excel_import_batch WHERE source_file_id=?",
                Long.class, file.sourceFileId());
        long periodCount = allKeys.stream().map(row -> row.get("source_type") + "|" + row.get("statistics_key"))
                .distinct().count();
        return new FileImpact(file.filenames().stream().distinct().toList(), batchCount == null ? 0 : batchCount,
                periodCount, allKeys.size(), otherKeys);
    }

    private static String selectedSnapshotQuery(LedgerWorkbookParser.SourceType sourceType) {
        String details = sourceType == LedgerWorkbookParser.SourceType.ARREARS
                ? "a.account_id AS stable_identity, a.group_code, a.group_name"
                : "l.member_service_number AS stable_identity, l.group_code, l.group_name";
        String table = sourceType == LedgerWorkbookParser.SourceType.ARREARS ? "arrears_snapshot a" : "line_expiry_snapshot l";
        String key = sourceType == LedgerWorkbookParser.SourceType.ARREARS
                ? "a.snapshot_record_id=s.id" : "l.snapshot_record_id=s.id";
        return """
                SELECT s.id AS snapshot_record_id, cv.source_type, cv.statistics_key, cv.identity_key,
                       cv.current_version_id, %s
                FROM excel_current_dataset cv
                JOIN excel_snapshot_record s ON s.id=cv.snapshot_record_id AND s.source_type=cv.source_type
                JOIN %s ON %s
                """.formatted(details, table, key);
    }

    private static SelectedRow mapSelected(ResultSet rs) throws SQLException {
        return new SelectedRow(rs.getLong("snapshot_record_id"), rs.getString("source_type"), rs.getString("statistics_key"),
                rs.getString("identity_key"), rs.getString("stable_identity"), rs.getString("group_code"),
                rs.getString("group_name"), rs.getLong("current_version_id"));
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record SelectedRow(long snapshotRecordId, String sourceType, String statisticsKey, String identityKey,
                               String stableIdentity, String groupCode, String groupName,
                               long currentVersionId) {}
    private record WorkOrderImpact(long count, long statusChanges, long followUps) {}
    private static class FileAccumulator {
        private final long id;
        private final String storageKey;
        private final Set<Long> batchIds = new LinkedHashSet<>();
        private final Set<String> filenames = new LinkedHashSet<>();
        private FileAccumulator(long id, String storageKey) { this.id = id; this.storageKey = storageKey; }
    }

    public record PreviewResult(String previewToken, Instant expiresAt, String sourceType,
                                List<SelectedRecord> selectedRecords, List<PeriodImpact> periods,
                                List<FileImpact> originalFiles, int maxSelectedRecords) {
        public PreviewResult {
            selectedRecords = List.copyOf(selectedRecords);
            periods = List.copyOf(periods);
            originalFiles = List.copyOf(originalFiles);
        }
    }
    public record SelectedRecord(long snapshotRecordId, String statisticsKey, String stableIdentity,
                                 String groupCode, String groupName) {}
    public record PeriodImpact(String statisticsKey, int selectedRecords, long historicalImportRows,
                               long snapshots, long affectedBatches, long versions, long revisionDiffs,
                               long assignmentExceptions, long assignmentHistory, long workOrders,
                               long workOrderStatusChanges, long workOrderFollowUps) {}
    public record FileImpact(List<String> filenames, long referenceBatches, long affectedPeriods,
                             long parsedBusinessKeys, long otherBusinessKeys) {
        public FileImpact { filenames = List.copyOf(filenames); }
    }
}
