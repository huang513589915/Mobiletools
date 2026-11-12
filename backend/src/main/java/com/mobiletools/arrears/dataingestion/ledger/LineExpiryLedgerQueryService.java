package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Current-version queries for independently stored line-expiry snapshots. */
@Service
public class LineExpiryLedgerQueryService {

    private static final DateTimeFormatter KEY_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> RENEWAL_STATUSES = List.of("待处理", "跟进中", "已续签", "不续签");
    private static final String FROM = """
            FROM excel_current_dataset cv
            JOIN excel_import_batch b ON b.id=cv.batch_id
                AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
            JOIN line_expiry_snapshot l ON l.snapshot_record_id=cv.snapshot_record_id AND l.batch_id=b.id AND l.source_type='LINE_EXPIRY'
            JOIN excel_snapshot_record s ON s.id=l.snapshot_record_id
                AND s.batch_id=b.id AND s.source_type='LINE_EXPIRY'
            LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
            LEFT JOIN line_renewal_work_order w ON w.current_snapshot_record_id=s.id
            LEFT JOIN app_user h ON h.id=w.last_handled_by_user_id
            """;
    private static final String COLUMNS = """
            s.id AS snapshot_record_id, cv.statistics_key, l.group_code, l.group_name,
            l.product_code, l.product_name, l.member_service_number, s.excel_manager_name,
            s.effective_manager_user_id, u.real_name AS effective_manager_name, l.billing_cutoff_date,
            w.id AS work_order_id, COALESCE(w.status, '待处理') AS renewal_status,
            w.last_handled_by_user_id, h.real_name AS last_handled_by_name, w.last_handled_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public LineExpiryLedgerQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.systemDefaultZone());
    }

    public LineExpiryLedgerQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(Filters filters, SnapshotAccessScopeService.Scope scope) {
        LocalDate currentDate = LocalDate.now(clock);
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(currentDate);
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='LINE_EXPIRY' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);
        if (selectedKey == null && !dateRange) {
            return new Page(List.of(), 0, request.page(), request.pageSize(), null,
                    availableKeys, RENEWAL_STATUSES, currentDate);
        }

        StringBuilder where = new StringBuilder(" WHERE cv.source_type='LINE_EXPIRY'");
        List<Object> args = new ArrayList<>();
        appendStatisticsDateFilter(request, selectedKey, where, args);
        if (request.groupQuery() != null) {
            where.append(" AND (l.group_code LIKE ? OR l.group_name LIKE ?)");
            String pattern = like(request.groupQuery());
            args.add(pattern);
            args.add(pattern);
        }
        if (request.productQuery() != null) {
            where.append(" AND (l.product_code LIKE ? OR l.product_name LIKE ?)");
            String pattern = like(request.productQuery());
            args.add(pattern);
            args.add(pattern);
        }
        if (request.effectiveManagerUserId() != null) {
            where.append(" AND s.effective_manager_user_id=?");
            args.add(request.effectiveManagerUserId());
        }
        if (request.renewalStatus() != null) {
            where.append(" AND COALESCE(w.status, '待处理')=?");
            args.add(request.renewalStatus());
        }
        String remainingExpression = "TIMESTAMPDIFF(DAY, ?, l.billing_cutoff_date)";
        if (request.remainingDaysMin() != null) {
            where.append(" AND ").append(remainingExpression).append(">=?");
            args.add(java.sql.Date.valueOf(currentDate));
            args.add(request.remainingDaysMin());
        }
        if (request.remainingDaysMax() != null) {
            where.append(" AND ").append(remainingExpression).append("<=?");
            args.add(java.sql.Date.valueOf(currentDate));
            args.add(request.remainingDaysMax());
        }
        scope.appendPredicate("s", where, args);

        Long count = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(0, java.sql.Date.valueOf(currentDate));
        rowArgs.add(request.pageSize());
        rowArgs.add((long) (request.page() - 1) * request.pageSize());
        List<Item> items = jdbc.query("SELECT " + COLUMNS + ", " + remainingExpression
                        + " AS remaining_days " + FROM + where
                        + " ORDER BY remaining_days, l.group_code, l.member_service_number LIMIT ? OFFSET ?",
                (rs, row) -> new Item(rs.getLong("snapshot_record_id"), rs.getString("statistics_key"),
                        rs.getString("group_code"), rs.getString("group_name"), rs.getString("product_code"),
                        rs.getString("product_name"), rs.getString("member_service_number"),
                        rs.getString("excel_manager_name"), nullableLong(rs, "effective_manager_user_id"),
                        rs.getString("effective_manager_name"), rs.getObject("billing_cutoff_date", LocalDate.class),
                        rs.getInt("remaining_days"), nullableLong(rs, "work_order_id"),
                        rs.getString("renewal_status"), nullableLong(rs, "last_handled_by_user_id"),
                        rs.getString("last_handled_by_name"), nullableDateTime(rs, "last_handled_at")), rowArgs.toArray());
        return new Page(items, count == null ? 0 : count, request.page(), request.pageSize(), selectedKey,
                availableKeys, RENEWAL_STATUSES, currentDate);
    }

    @Transactional(readOnly = true)
    public Detail detail(long snapshotRecordId, SnapshotAccessScopeService.Scope scope) {
        if (snapshotRecordId <= 0) throw new IllegalArgumentException("snapshotRecordId 必须为正数");
        LocalDate currentDate = LocalDate.now(clock);
        StringBuilder where = new StringBuilder(" WHERE s.id=? AND cv.source_type='LINE_EXPIRY'");
        List<Object> args = new ArrayList<>();
        args.add(snapshotRecordId);
        scope.appendPredicate("s", where, args);
        List<DetailRow> matches = jdbc.query("""
                SELECT s.id AS snapshot_record_id, cv.statistics_key, b.id AS batch_id,
                       b.original_filename, s.excel_manager_name, s.effective_manager_user_id,
                       u.real_name AS effective_manager_name, r.sheet_name, r.source_row_number,
                       r.identity_key, cv.effective_raw_fields AS raw_fields,
                       cv.effective_normalized_fields AS normalized_fields,
                       l.group_code, l.group_name, l.product_code, l.product_name,
                       l.member_service_number, l.billing_cutoff_date,
                       w.id AS work_order_id, COALESCE(w.status, '待处理') AS renewal_status,
                       w.last_handled_by_user_id, h.real_name AS last_handled_by_name, w.last_handled_at
                FROM excel_current_dataset cv
                JOIN excel_import_batch b ON b.id=cv.batch_id
                    AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
                JOIN line_expiry_snapshot l ON l.snapshot_record_id=cv.snapshot_record_id AND l.batch_id=b.id AND l.source_type='LINE_EXPIRY'
                JOIN excel_snapshot_record s ON s.id=l.snapshot_record_id
                    AND s.batch_id=b.id AND s.source_type='LINE_EXPIRY'
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=b.id
                LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
                LEFT JOIN line_renewal_work_order w ON w.current_snapshot_record_id=s.id
                LEFT JOIN app_user h ON h.id=w.last_handled_by_user_id
                """ + where, (rs, row) -> new DetailRow(rs.getLong("snapshot_record_id"),
                rs.getString("statistics_key"), rs.getLong("batch_id"), rs.getString("original_filename"),
                rs.getString("excel_manager_name"), nullableLong(rs, "effective_manager_user_id"),
                rs.getString("effective_manager_name"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("identity_key"), readJson(rs.getString("raw_fields")),
                readJson(rs.getString("normalized_fields")), rs.getString("group_code"), rs.getString("group_name"),
                rs.getString("product_code"), rs.getString("product_name"), rs.getString("member_service_number"),
                rs.getObject("billing_cutoff_date", LocalDate.class), nullableLong(rs, "work_order_id"),
                rs.getString("renewal_status"), nullableLong(rs, "last_handled_by_user_id"),
                rs.getString("last_handled_by_name"), nullableDateTime(rs, "last_handled_at")), args.toArray());
        if (matches.isEmpty()) throw new AccessDeniedException("专线记录不存在或无权查看");
        DetailRow row = matches.get(0);
        Item item = new Item(row.snapshotRecordId(), row.statisticsKey(), row.groupCode(), row.groupName(),
                row.productCode(), row.productName(), row.memberServiceNumber(), row.excelManagerName(),
                row.effectiveManagerUserId(), row.effectiveManagerName(), row.billingCutoffDate(),
                (int) (row.billingCutoffDate().toEpochDay() - currentDate.toEpochDay()), row.workOrderId(),
                row.renewalStatus(), row.lastHandledByUserId(), row.lastHandledByName(), row.lastHandledAt());
        String assignmentStatus = row.effectiveManagerUserId() == null
                ? "UNASSIGNED_EXCEPTION" : "ASSIGNED";
        List<StatusHistory> history = row.workOrderId() == null ? List.of() : jdbc.query("""
                SELECT h.id, h.previous_status, h.new_status, h.changed_by_user_id,
                       u.real_name AS changed_by_name, h.changed_at
                FROM line_renewal_status_history h
                LEFT JOIN app_user u ON u.id=h.changed_by_user_id
                WHERE h.work_order_id=? ORDER BY h.changed_at, h.id
                """, (rs, index) -> new StatusHistory(rs.getLong("id"), rs.getString("previous_status"),
                rs.getString("new_status"), rs.getLong("changed_by_user_id"),
                rs.getString("changed_by_name"), rs.getTimestamp("changed_at").toLocalDateTime()), row.workOrderId());
        List<HandlingTimelineEvent> timeline = new ArrayList<>();
        for (StatusHistory event : history) {
            timeline.add(new HandlingTimelineEvent(event.sourceId(), "STATUS", event.previousStatus(), event.newStatus(),
                    null, event.changedByUserId(), event.changedByName(), event.changedAt()));
        }
        if (row.workOrderId() != null) {
            timeline.addAll(jdbc.query("""
                    SELECT f.id, f.note, f.created_by_user_id, u.real_name AS actor_name, f.created_at
                    FROM line_renewal_work_order_follow_up f
                    LEFT JOIN app_user u ON u.id=f.created_by_user_id
                    WHERE f.work_order_id=?
                    """, (rs, index) -> new HandlingTimelineEvent(rs.getLong("id"), "FOLLOW_UP",
                    null, null, rs.getString("note"), rs.getLong("created_by_user_id"),
                    rs.getString("actor_name"), rs.getTimestamp("created_at").toLocalDateTime()), row.workOrderId()));
        }
        timeline.sort(Comparator.comparing(HandlingTimelineEvent::happenedAt)
                .thenComparingInt(event -> "STATUS".equals(event.type()) ? 0 : 1)
                .thenComparingLong(HandlingTimelineEvent::sourceId));
        return new Detail(item, row.batchId(), row.originalFilename(), row.sheetName(), row.sourceRowNumber(),
                row.identityKey(), row.rawFields(), row.normalizedFields(), assignmentStatus, currentDate,
                RENEWAL_STATUSES, history, timeline);
    }

    /** Exports the complete server-filtered line result without the read-time remaining-days column. */
    @Transactional(readOnly = true)
    public byte[] export(Filters filters, SnapshotAccessScopeService.Scope scope) {
        LocalDate currentDate = LocalDate.now(clock);
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(currentDate);
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='LINE_EXPIRY' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);
        StringBuilder where = new StringBuilder(" WHERE cv.source_type='LINE_EXPIRY'");
        List<Object> args = new ArrayList<>();
        if (selectedKey == null && !dateRange) {
            where.append(" AND 1=0");
        } else {
            appendStatisticsDateFilter(request, selectedKey, where, args);
            appendFilters(request, currentDate, where, args);
            scope.appendPredicate("s", where, args);
        }
        List<ExportRow> rows = jdbc.query("""
                SELECT cv.effective_raw_fields AS raw_fields, s.effective_manager_user_id,
                       u.real_name AS effective_manager_name, b.id AS source_batch_id,
                       b.original_filename, r.sheet_name, r.source_row_number,
                       cv.field_provenance AS field_provenance
                FROM excel_current_dataset cv
                JOIN excel_import_batch b ON b.id=cv.batch_id
                    AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
                JOIN line_expiry_snapshot l ON l.snapshot_record_id=cv.snapshot_record_id AND l.batch_id=b.id AND l.source_type='LINE_EXPIRY'
                JOIN excel_snapshot_record s ON s.id=l.snapshot_record_id
                    AND s.batch_id=b.id AND s.source_type='LINE_EXPIRY'
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=b.id
                LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
                LEFT JOIN line_renewal_work_order w ON w.current_snapshot_record_id=s.id
                """ + where + " ORDER BY l.billing_cutoff_date, l.group_code, l.member_service_number",
                (rs, row) -> new ExportRow(readJson(rs.getString("raw_fields")),
                        rs.getString("effective_manager_name"), rs.getLong("source_batch_id"),
                        rs.getString("original_filename"), rs.getString("sheet_name"),
                        rs.getInt("source_row_number"), rs.getString("field_provenance")), args.toArray());
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("专线到期明细");
            List<LedgerRawFieldExport.Column> columns = LedgerRawFieldExport.columns(
                    rows.stream().map(ExportRow::rawFields).toList());
            var headerRow = sheet.createRow(0);
            int column = 0;
            for (LedgerRawFieldExport.Column field : columns) headerRow.createCell(column++).setCellValue(field.label());
            headerRow.createCell(column).setCellValue("当前有效客户经理");
            headerRow.createCell(column + 1).setCellValue("来源批次");
            headerRow.createCell(column + 2).setCellValue("来源文件");
            headerRow.createCell(column + 3).setCellValue("来源工作表");
            headerRow.createCell(column + 4).setCellValue("来源原始行号");
            headerRow.createCell(column + 5).setCellValue("字段来源（JSON）");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                ExportRow exportRow = rows.get(rowIndex);
                var values = LedgerRawFieldExport.values(exportRow.rawFields());
                var row = sheet.createRow(rowIndex + 1);
                column = 0;
                for (LedgerRawFieldExport.Column field : columns) {
                    row.createCell(column++).setCellValue(values.getOrDefault(field.key(), ""));
                }
                row.createCell(column).setCellValue(exportRow.effectiveManagerName() == null
                        ? "未分配（归属匹配异常）" : exportRow.effectiveManagerName());
                row.createCell(column + 1).setCellValue(exportRow.batchId());
                row.createCell(column + 2).setCellValue(exportRow.originalFilename());
                row.createCell(column + 3).setCellValue(exportRow.sheetName());
                row.createCell(column + 4).setCellValue(exportRow.sourceRowNumber());
                row.createCell(column + 5).setCellValue(exportRow.fieldProvenance() == null
                        ? "{}" : exportRow.fieldProvenance());
            }
            for (int i = 0; i < columns.size() + 6; i++) sheet.autoSizeColumn(i);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("专线导出文件生成失败", exception);
        }
    }

    /** Exports a fixed-field renewal report. Only administrators may retrieve it. */
    @Transactional(readOnly = true)
    public byte[] exportRenewalReport(Filters filters, SnapshotAccessScopeService.Scope scope) {
        if (scope == null || !scope.administrator()) {
            throw new AccessDeniedException("仅管理员可导出续签工单报表");
        }
        LocalDate currentDate = LocalDate.now(clock);
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(currentDate);
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='LINE_EXPIRY' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);
        StringBuilder where = new StringBuilder(" WHERE cv.source_type='LINE_EXPIRY'");
        List<Object> args = new ArrayList<>();
        if (selectedKey == null && !dateRange) where.append(" AND 1=0");
        else appendStatisticsDateFilter(request, selectedKey, where, args);
        appendFilters(request, currentDate, where, args);
        scope.appendPredicate("s", where, args);
        List<RenewalExportRow> rows = jdbc.query("""
                SELECT cv.statistics_key, l.group_code, l.group_name, l.product_code, l.product_name,
                       l.member_service_number, s.excel_manager_name, u.real_name AS effective_manager_name,
                       l.billing_cutoff_date, COALESCE(w.status, '待处理') AS renewal_status,
                       h.real_name AS last_handled_by_name, w.last_handled_at
                """ + FROM + where + " ORDER BY cv.statistics_key, l.group_code, l.member_service_number",
                (rs, row) -> new RenewalExportRow(rs.getString("statistics_key"), rs.getString("group_code"),
                        rs.getString("group_name"), rs.getString("product_code"), rs.getString("product_name"),
                        rs.getString("member_service_number"), rs.getString("excel_manager_name"),
                        rs.getString("effective_manager_name"), rs.getObject("billing_cutoff_date", LocalDate.class),
                        rs.getString("renewal_status"), rs.getString("last_handled_by_name"),
                        nullableDateTime(rs, "last_handled_at")), args.toArray());
        String[] headers = {"统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码",
                "Excel 原始客户经理", "当前有效客户经理", "计费截止日期", "状态", "最后处理人", "最后处理时间"};
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("续签工单");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                RenewalExportRow data = rows.get(rowIndex);
                var row = sheet.createRow(rowIndex + 1);
                row.createCell(0).setCellValue(dateLabel(data.statisticsKey()));
                row.createCell(1).setCellValue(text(data.groupCode()));
                row.createCell(2).setCellValue(text(data.groupName()));
                row.createCell(3).setCellValue(text(data.productCode()));
                row.createCell(4).setCellValue(text(data.productName()));
                row.createCell(5).setCellValue(text(data.memberServiceNumber()));
                row.createCell(6).setCellValue(text(data.excelManagerName()));
                row.createCell(7).setCellValue(data.effectiveManagerName() == null
                        ? "未分配（归属匹配异常）" : data.effectiveManagerName());
                row.createCell(8).setCellValue(data.billingCutoffDate() == null ? "" : data.billingCutoffDate().toString());
                row.createCell(9).setCellValue(text(data.renewalStatus()));
                row.createCell(10).setCellValue(text(data.lastHandledByName()));
                row.createCell(11).setCellValue(data.lastHandledAt() == null ? "" : data.lastHandledAt().toString().replace('T', ' '));
            }
            for (int column = 0; column < headers.length; column++) sheet.autoSizeColumn(column);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("续签工单报表生成失败", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return objectMapper.createArrayNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("专线原始字段无法读取", exception);
        }
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String like(String value) { return "%" + value.strip() + "%"; }

    private static String text(String value) { return value == null ? "" : value; }

    private static String dateLabel(String key) {
        return key != null && key.length() == 8
                ? key.substring(0, 4) + "-" + key.substring(4, 6) + "-" + key.substring(6)
                : key == null ? "" : key;
    }

    private static void appendFilters(Filters request, LocalDate currentDate,
                                      StringBuilder where, List<Object> args) {
        if (request.groupQuery() != null) {
            where.append(" AND (l.group_code LIKE ? OR l.group_name LIKE ?)");
            String pattern = like(request.groupQuery());
            args.add(pattern);
            args.add(pattern);
        }
        if (request.productQuery() != null) {
            where.append(" AND (l.product_code LIKE ? OR l.product_name LIKE ?)");
            String pattern = like(request.productQuery());
            args.add(pattern);
            args.add(pattern);
        }
        if (request.effectiveManagerUserId() != null) {
            where.append(" AND s.effective_manager_user_id=?");
            args.add(request.effectiveManagerUserId());
        }
        if (request.renewalStatus() != null) {
            where.append(" AND COALESCE(w.status, '待处理')=?");
            args.add(request.renewalStatus());
        }
        String remainingExpression = "TIMESTAMPDIFF(DAY, ?, l.billing_cutoff_date)";
        if (request.remainingDaysMin() != null) {
            where.append(" AND ").append(remainingExpression).append(">=?");
            args.add(java.sql.Date.valueOf(currentDate));
            args.add(request.remainingDaysMin());
        }
        if (request.remainingDaysMax() != null) {
            where.append(" AND ").append(remainingExpression).append("<=?");
            args.add(java.sql.Date.valueOf(currentDate));
            args.add(request.remainingDaysMax());
        }
    }

    private static boolean hasStatisticsDateRange(Filters request) {
        return request.statisticsDateFrom() != null || request.statisticsDateTo() != null;
    }

    private static String latestMatchingKey(List<String> availableKeys, Filters request) {
        return availableKeys.stream()
                .filter(key -> request.statisticsDateFrom() == null || key.compareTo(request.statisticsDateFrom()) >= 0)
                .filter(key -> request.statisticsDateTo() == null || key.compareTo(request.statisticsDateTo()) <= 0)
                .findFirst().orElse(null);
    }

    private static void appendStatisticsDateFilter(Filters request, String defaultKey,
                                                   StringBuilder where, List<Object> args) {
        if (request.statisticsKey() != null) {
            where.append(" AND cv.statistics_key=?");
            args.add(request.statisticsKey());
            return;
        }
        if (request.statisticsDateFrom() != null) {
            where.append(" AND cv.statistics_key>=?");
            args.add(request.statisticsDateFrom());
        }
        if (request.statisticsDateTo() != null) {
            where.append(" AND cv.statistics_key<=?");
            args.add(request.statisticsDateTo());
        }
        if (!hasStatisticsDateRange(request) && defaultKey != null) {
            where.append(" AND cv.statistics_key=?");
            args.add(defaultKey);
        }
    }

    private static void validate(Filters filters) {
        if (filters.page() < 1) throw new IllegalArgumentException("page 必须为正数");
        if (!List.of(20, 50, 100).contains(filters.pageSize())) {
            throw new IllegalArgumentException("pageSize 仅支持 20、50 或 100");
        }
        String key = filters.statisticsKey();
        if (key != null) {
            if (!key.matches("[0-9]{8}")) throw new IllegalArgumentException("专线统计日期应为 yyyyMMdd");
            try { LocalDate.parse(key, KEY_FORMAT); }
            catch (java.time.DateTimeException exception) { throw new IllegalArgumentException("专线统计日期无效"); }
        }
        validateStatisticsDate(filters.statisticsDateFrom(), "起始统计日期");
        validateStatisticsDate(filters.statisticsDateTo(), "结束统计日期");
        if (filters.statisticsDateFrom() != null && filters.statisticsDateTo() != null
                && filters.statisticsDateFrom().compareTo(filters.statisticsDateTo()) > 0) {
            throw new IllegalArgumentException("起始统计日期不能晚于结束统计日期");
        }
        if (filters.effectiveManagerUserId() != null && filters.effectiveManagerUserId() <= 0) {
            throw new IllegalArgumentException("effectiveManagerUserId 必须为正数");
        }
        if (filters.renewalStatus() != null && !List.of("待处理", "跟进中", "已续签", "不续签").contains(filters.renewalStatus())) {
            throw new IllegalArgumentException("续签处理状态无效");
        }
        if (filters.remainingDaysMin() != null && filters.remainingDaysMax() != null
                && filters.remainingDaysMin() > filters.remainingDaysMax()) {
            throw new IllegalArgumentException("剩余天数最小值不能大于最大值");
        }
    }

    private static void validateStatisticsDate(String key, String label) {
        if (key == null) return;
        if (!key.matches("[0-9]{8}")) throw new IllegalArgumentException(label + "应为 yyyyMMdd");
        try { LocalDate.parse(key, KEY_FORMAT); }
        catch (java.time.DateTimeException exception) { throw new IllegalArgumentException(label + "无效"); }
    }

    public record Filters(String statisticsKey, String groupQuery, String productQuery,
                          Long effectiveManagerUserId, Integer remainingDaysMin, Integer remainingDaysMax,
                          int page, int pageSize, String statisticsDateFrom, String statisticsDateTo,
                          String renewalStatus) {
        public Filters(String statisticsKey, String groupQuery, String productQuery,
                       Long effectiveManagerUserId, Integer remainingDaysMin, Integer remainingDaysMax,
                       int page, int pageSize) {
            this(statisticsKey, groupQuery, productQuery, effectiveManagerUserId, remainingDaysMin,
                    remainingDaysMax, page, pageSize, null, null, null);
        }

        public Filters(String statisticsKey, String groupQuery, String productQuery,
                       Long effectiveManagerUserId, Integer remainingDaysMin, Integer remainingDaysMax,
                       int page, int pageSize, String statisticsDateFrom, String statisticsDateTo) {
            this(statisticsKey, groupQuery, productQuery, effectiveManagerUserId, remainingDaysMin,
                    remainingDaysMax, page, pageSize, statisticsDateFrom, statisticsDateTo, null);
        }

        public static Filters defaults() { return new Filters(null, null, null, null, null, null, 1, 20, null, null, null); }
        private Filters withDefaults(LocalDate currentDate) {
            boolean currentMonthDefault = statisticsKey == null
                    && (statisticsDateFrom == null || statisticsDateFrom.isBlank())
                    && (statisticsDateTo == null || statisticsDateTo.isBlank());
            LocalDate monthStart = currentDate.withDayOfMonth(1);
            LocalDate monthEnd = currentDate.withDayOfMonth(currentDate.lengthOfMonth());
            return new Filters(statisticsKey, clean(groupQuery), clean(productQuery), effectiveManagerUserId,
                    remainingDaysMin, remainingDaysMax, page == 0 ? 1 : page, pageSize == 0 ? 20 : pageSize,
                    currentMonthDefault ? monthStart.format(KEY_FORMAT) : clean(statisticsDateFrom),
                    currentMonthDefault ? monthEnd.format(KEY_FORMAT) : clean(statisticsDateTo),
                    clean(renewalStatus));
        }
        private static String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    }

    public record Item(long snapshotRecordId, String statisticsKey, String groupCode, String groupName,
                       String productCode, String productName, String memberServiceNumber,
                       String excelManagerName, Long effectiveManagerUserId, String effectiveManagerName,
                       @JsonFormat(pattern = "yyyy-MM-dd") LocalDate billingCutoffDate, int remainingDays,
                       Long workOrderId, String renewalStatus, Long lastHandledByUserId, String lastHandledByName,
                       @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime lastHandledAt) {
        public Item(long snapshotRecordId, String statisticsKey, String groupCode, String groupName,
                    String productCode, String productName, String memberServiceNumber,
                    String excelManagerName, Long effectiveManagerUserId, String effectiveManagerName,
                    LocalDate billingCutoffDate, int remainingDays) {
            this(snapshotRecordId, statisticsKey, groupCode, groupName, productCode, productName,
                    memberServiceNumber, excelManagerName, effectiveManagerUserId, effectiveManagerName,
                    billingCutoffDate, remainingDays, null, "待处理", null, null, null);
        }
    }

    public record Detail(Item item, long batchId, String originalFilename, String sheetName,
                         int sourceRowNumber, String identityKey, JsonNode rawFields,
                         JsonNode normalizedFields, String assignmentStatus,
                         @JsonFormat(pattern = "yyyy-MM-dd") LocalDate calculatedAt,
                         List<String> availableRenewalStatuses,
                         List<StatusHistory> statusHistory,
                         List<HandlingTimelineEvent> handlingTimeline) {
        public Detail(Item item, long batchId, String originalFilename, String sheetName,
                      int sourceRowNumber, String identityKey, JsonNode rawFields,
                      JsonNode normalizedFields, String assignmentStatus, LocalDate calculatedAt) {
            this(item, batchId, originalFilename, sheetName, sourceRowNumber, identityKey, rawFields,
                    normalizedFields, assignmentStatus, calculatedAt, RENEWAL_STATUSES, List.of(), List.of());
        }
        public Detail(Item item, long batchId, String originalFilename, String sheetName,
                      int sourceRowNumber, String identityKey, JsonNode rawFields,
                      JsonNode normalizedFields, String assignmentStatus, LocalDate calculatedAt,
                      List<String> availableRenewalStatuses, List<StatusHistory> statusHistory) {
            this(item, batchId, originalFilename, sheetName, sourceRowNumber, identityKey, rawFields,
                    normalizedFields, assignmentStatus, calculatedAt, availableRenewalStatuses,
                    statusHistory, List.of());
        }
        public Detail {
            availableRenewalStatuses = List.copyOf(availableRenewalStatuses);
            statusHistory = List.copyOf(statusHistory);
            handlingTimeline = List.copyOf(handlingTimeline);
        }
    }

    public record StatusHistory(long sourceId, String previousStatus, String newStatus, long changedByUserId,
                                String changedByName,
                                @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime changedAt) {
        public StatusHistory(String previousStatus, String newStatus, long changedByUserId,
                             String changedByName, LocalDateTime changedAt) {
            this(0, previousStatus, newStatus, changedByUserId, changedByName, changedAt);
        }
    }

    public record HandlingTimelineEvent(long sourceId, String type, String previousStatus, String newStatus,
                                        String note, long actorUserId, String actorName,
                                        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime happenedAt) {}

    private record DetailRow(long snapshotRecordId, String statisticsKey, long batchId,
                             String originalFilename, String excelManagerName, Long effectiveManagerUserId,
                             String effectiveManagerName, String sheetName, int sourceRowNumber,
                             String identityKey, JsonNode rawFields, JsonNode normalizedFields,
                             String groupCode, String groupName, String productCode, String productName,
                             String memberServiceNumber, LocalDate billingCutoffDate, Long workOrderId,
                             String renewalStatus, Long lastHandledByUserId, String lastHandledByName,
                             LocalDateTime lastHandledAt) {}

    private record ExportRow(JsonNode rawFields, String effectiveManagerName, long batchId,
                             String originalFilename, String sheetName, int sourceRowNumber,
                             String fieldProvenance) {}
    private record RenewalExportRow(String statisticsKey, String groupCode, String groupName,
                                    String productCode, String productName, String memberServiceNumber,
                                    String excelManagerName, String effectiveManagerName, LocalDate billingCutoffDate,
                                    String renewalStatus, String lastHandledByName, LocalDateTime lastHandledAt) {}

    private static LocalDateTime nullableDateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record Page(List<Item> items, long total, int page, int pageSize, String statisticsKey,
                       List<String> availableStatisticsKeys, List<String> availableRenewalStatuses,
                       @JsonFormat(pattern = "yyyy-MM-dd") LocalDate calculatedAt) {
        public Page(List<Item> items, long total, int page, int pageSize, String statisticsKey,
                    List<String> availableStatisticsKeys,
                    LocalDate calculatedAt) {
            this(items, total, page, pageSize, statisticsKey, availableStatisticsKeys,
                    RENEWAL_STATUSES, calculatedAt);
        }
        public Page {
            items = List.copyOf(items);
            availableStatisticsKeys = List.copyOf(availableStatisticsKeys);
            availableRenewalStatuses = List.copyOf(availableRenewalStatuses);
        }
    }
}
