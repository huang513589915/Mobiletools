package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;

/** Read API for the current version of each independent arrears-month snapshot. */
@Service
public class ArrearsLedgerQueryService {

    private static final String FROM = """
            FROM excel_current_dataset cv
            JOIN excel_import_batch b ON b.id=cv.batch_id
                AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
            JOIN arrears_snapshot a ON a.snapshot_record_id=cv.snapshot_record_id AND a.batch_id=b.id AND a.source_type='ARREARS'
            JOIN excel_snapshot_record s ON s.id=a.snapshot_record_id
                AND s.batch_id=b.id AND s.source_type='ARREARS'
            LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
            LEFT JOIN arrears_work_order w ON w.current_snapshot_record_id=s.id
            LEFT JOIN app_user h ON h.id=w.last_handled_by_user_id
            """;
    private static final String COLUMNS = """
            s.id AS snapshot_record_id, cv.statistics_key, a.group_code, a.group_name, s.excel_manager_name,
            s.effective_manager_user_id, u.real_name AS effective_manager_name,
            a.account_id, a.carrying_product_name, a.ending_arrears_amount, a.aging_value,
            w.id AS work_order_id, COALESCE(w.status, '待处理') AS work_order_status,
            w.last_handled_by_user_id, h.real_name AS last_handled_by_name, w.last_handled_at
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ArrearsWorkOrderService workOrders;
    private final Clock clock;

    public ArrearsLedgerQueryService(JdbcTemplate jdbc) {
        this(jdbc, new ObjectMapper(), Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    @Autowired
    public ArrearsLedgerQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    public ArrearsLedgerQueryService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.workOrders = new ArrearsWorkOrderService(jdbc);
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page list(Filters filters, SnapshotAccessScopeService.Scope scope) {
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(YearMonth.now(clock));
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='ARREARS' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);

        if (selectedKey == null && !dateRange) {
            return new Page(List.of(), 0, request.page(), request.pageSize(), null, availableKeys);
        }

        StringBuilder where = new StringBuilder(" WHERE cv.source_type='ARREARS'");
        List<Object> args = new ArrayList<>();
        appendStatisticsDateFilter(request, selectedKey, where, args);
        if (request.groupQuery() != null) {
            where.append(" AND (a.group_code LIKE ? OR a.group_name LIKE ?)");
            String pattern = like(request.groupQuery());
            args.add(pattern);
            args.add(pattern);
        }
        contains(where, args, "a.account_id", request.accountId());
        contains(where, args, "a.carrying_product_name", request.carryingProductName());
        if (request.effectiveManagerUserId() != null) {
            where.append(" AND s.effective_manager_user_id=?");
            args.add(request.effectiveManagerUserId());
        }
        if (request.workOrderStatus() != null) {
            where.append(" AND w.status=?");
            args.add(request.workOrderStatus());
        }
        scope.appendPredicate("s", where, args);

        Long count = jdbc.queryForObject("SELECT COUNT(*) " + FROM + where, Long.class, args.toArray());
        List<Object> rowArgs = new ArrayList<>(args);
        rowArgs.add(request.pageSize());
        rowArgs.add((long) (request.page() - 1) * request.pageSize());
        List<Item> items = jdbc.query("SELECT " + COLUMNS + FROM + where
                        + " ORDER BY a.group_code, a.account_id LIMIT ? OFFSET ?",
                (rs, row) -> new Item(rs.getLong("snapshot_record_id"), rs.getString("statistics_key"), rs.getString("group_code"),
                        rs.getString("group_name"), rs.getString("excel_manager_name"),
                        nullableLong(rs, "effective_manager_user_id"), rs.getString("effective_manager_name"),
                        rs.getString("account_id"), rs.getString("carrying_product_name"),
                        rs.getBigDecimal("ending_arrears_amount"), rs.getString("aging_value"),
                        nullableLong(rs, "work_order_id"), rs.getString("work_order_status"),
                        nullableLong(rs, "last_handled_by_user_id"), rs.getString("last_handled_by_name"),
                        nullableDateTime(rs, "last_handled_at")),
                rowArgs.toArray());
        return new Page(items, count == null ? 0 : count, request.page(), request.pageSize(),
                selectedKey, availableKeys);
    }

    @Transactional(readOnly = true)
    public Detail detail(long snapshotRecordId, SnapshotAccessScopeService.Scope scope) {
        if (snapshotRecordId <= 0) throw new IllegalArgumentException("snapshotRecordId 必须为正数");
        StringBuilder where = new StringBuilder(" WHERE s.id=? AND cv.source_type='ARREARS'");
        List<Object> args = new ArrayList<>();
        args.add(snapshotRecordId);
        scope.appendPredicate("s", where, args);
        List<DetailRow> matches = jdbc.query("""
                SELECT s.id AS snapshot_record_id, cv.statistics_key, b.id AS batch_id,
                       b.original_filename, s.excel_manager_name, s.effective_manager_user_id,
                       u.real_name AS effective_manager_name, r.sheet_name, r.source_row_number,
                       r.identity_key, cv.effective_raw_fields AS raw_fields,
                       cv.effective_normalized_fields AS normalized_fields,
                       a.group_code, a.group_name, a.account_id, a.carrying_product_name,
                       a.ending_arrears_amount, a.aging_value, e.status AS exception_status,
                       w.id AS work_order_id, COALESCE(w.status, '待处理') AS work_order_status,
                       w.last_handled_by_user_id, h.real_name AS last_handled_by_name, w.last_handled_at
                FROM excel_current_dataset cv
                JOIN excel_import_batch b ON b.id=cv.batch_id
                    AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
                JOIN arrears_snapshot a ON a.snapshot_record_id=cv.snapshot_record_id AND a.batch_id=b.id AND a.source_type='ARREARS'
                JOIN excel_snapshot_record s ON s.id=a.snapshot_record_id
                    AND s.batch_id=b.id AND s.source_type='ARREARS'
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=b.id
                LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
                LEFT JOIN assignment_exception e ON e.snapshot_record_id=s.id
                LEFT JOIN arrears_work_order w ON w.current_snapshot_record_id=s.id
                LEFT JOIN app_user h ON h.id=w.last_handled_by_user_id
                """ + where, (rs, row) -> new DetailRow(rs.getLong("snapshot_record_id"),
                rs.getString("statistics_key"), rs.getLong("batch_id"), rs.getString("original_filename"),
                rs.getString("excel_manager_name"), nullableLong(rs, "effective_manager_user_id"),
                rs.getString("effective_manager_name"), rs.getString("sheet_name"),
                rs.getInt("source_row_number"), rs.getString("identity_key"),
                readJson(rs.getString("raw_fields")), readJson(rs.getString("normalized_fields")),
                rs.getString("group_code"), rs.getString("group_name"), rs.getString("account_id"),
                rs.getString("carrying_product_name"), rs.getBigDecimal("ending_arrears_amount"),
                rs.getString("aging_value"), rs.getString("exception_status"),
                nullableLong(rs, "work_order_id"), rs.getString("work_order_status"),
                nullableLong(rs, "last_handled_by_user_id"), rs.getString("last_handled_by_name"),
                nullableDateTime(rs, "last_handled_at")), args.toArray());
        if (matches.isEmpty()) throw new AccessDeniedException("欠费记录不存在或无权查看");
        DetailRow row = matches.get(0);
        Item item = new Item(row.snapshotRecordId(), row.statisticsKey(), row.groupCode(), row.groupName(),
                row.excelManagerName(), row.effectiveManagerUserId(), row.effectiveManagerName(), row.accountId(),
                row.carryingProductName(), row.endingArrearsAmount(), row.agingValue(), row.workOrderId(),
                row.workOrderStatus(), row.lastHandledByUserId(), row.lastHandledByName(), row.lastHandledAt());
        String assignmentStatus = row.effectiveManagerUserId() == null
                ? "UNASSIGNED_EXCEPTION" : "RESOLVED".equals(row.exceptionStatus()) ? "MANUALLY_ASSIGNED" : "ASSIGNED";
        return new Detail(item, row.batchId(), row.originalFilename(), row.sheetName(), row.sourceRowNumber(),
                row.identityKey(), row.rawFields(), row.normalizedFields(), assignmentStatus,
                row.workOrderId() == null ? List.of() : workOrders.timeline(row.workOrderId()));
    }

    /** Exports the complete server-filtered result, retaining every raw source column. */
    @Transactional(readOnly = true)
    public byte[] export(Filters filters, SnapshotAccessScopeService.Scope scope) {
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(YearMonth.now(clock));
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='ARREARS' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE cv.source_type='ARREARS'");
        if (selectedKey == null && !dateRange) {
            where.append(" AND 1=0");
        } else {
            appendStatisticsDateFilter(request, selectedKey, where, args);
            appendFilters(request, where, args, true);
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
                JOIN arrears_snapshot a ON a.snapshot_record_id=cv.snapshot_record_id AND a.batch_id=b.id AND a.source_type='ARREARS'
                JOIN excel_snapshot_record s ON s.id=a.snapshot_record_id
                    AND s.batch_id=b.id AND s.source_type='ARREARS'
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=b.id
                LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
                LEFT JOIN arrears_work_order w ON w.current_snapshot_record_id=s.id
                """ + where + " ORDER BY a.group_code, a.account_id",
                (rs, row) -> new ExportRow(readJson(rs.getString("raw_fields")),
                        rs.getString("effective_manager_name"), rs.getLong("source_batch_id"),
                        rs.getString("original_filename"), rs.getString("sheet_name"),
                        rs.getInt("source_row_number"), rs.getString("field_provenance")), args.toArray());
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("欠费明细");
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
            throw new IllegalStateException("欠费导出文件生成失败", exception);
        }
    }

    /** Exports the administrator-only monthly work-order report, independent from raw arrears export. */
    @Transactional(readOnly = true)
    public byte[] exportWorkOrders(Filters filters, SnapshotAccessScopeService.Scope scope) {
        if (scope == null || !scope.administrator()) throw new AccessDeniedException("仅管理员可导出欠费工单报表");
        Filters request = filters == null ? Filters.defaults() : filters;
        request = request.withDefaults(YearMonth.now(clock));
        validate(request);
        List<String> availableKeys = jdbc.queryForList("""
                SELECT DISTINCT statistics_key FROM excel_current_dataset
                WHERE source_type='ARREARS' ORDER BY statistics_key DESC
                """, String.class);
        boolean dateRange = hasStatisticsDateRange(request);
        String selectedKey = request.statisticsKey() == null && !dateRange
                ? availableKeys.stream().findFirst().orElse(null)
                : request.statisticsKey() != null ? request.statisticsKey() : latestMatchingKey(availableKeys, request);
        StringBuilder where = new StringBuilder(" WHERE cv.source_type='ARREARS'");
        List<Object> args = new ArrayList<>();
        if (selectedKey == null && !dateRange) where.append(" AND 1=0");
        else appendStatisticsDateFilter(request, selectedKey, where, args);
        appendFilters(request, where, args, true);
        List<WorkOrderExportRow> rows = jdbc.query("""
                SELECT cv.statistics_key, a.group_code, a.group_name, s.excel_manager_name,
                       u.real_name AS effective_manager_name, a.account_id, a.carrying_product_name,
                       a.ending_arrears_amount, a.aging_value, w.status,
                       h.real_name AS last_handled_by_name, w.last_handled_at
                FROM excel_current_dataset cv
                JOIN excel_import_batch b ON b.id=cv.batch_id
                    AND b.source_type=cv.source_type AND b.statistics_key=cv.statistics_key
                JOIN arrears_snapshot a ON a.snapshot_record_id=cv.snapshot_record_id AND a.batch_id=b.id AND a.source_type='ARREARS'
                JOIN excel_snapshot_record s ON s.id=a.snapshot_record_id
                    AND s.batch_id=b.id AND s.source_type='ARREARS'
                JOIN arrears_work_order w ON w.current_snapshot_record_id=s.id
                LEFT JOIN app_user u ON u.id=s.effective_manager_user_id
                LEFT JOIN app_user h ON h.id=w.last_handled_by_user_id
                """ + where + " ORDER BY cv.statistics_key, a.group_code, a.account_id",
                (rs, index) -> new WorkOrderExportRow(rs.getString("statistics_key"),
                        rs.getString("group_code"), rs.getString("group_name"), rs.getString("excel_manager_name"),
                        rs.getString("effective_manager_name"), rs.getString("account_id"),
                        rs.getString("carrying_product_name"), rs.getBigDecimal("ending_arrears_amount"),
                        rs.getString("aging_value"), rs.getString("status"),
                        rs.getString("last_handled_by_name"), nullableDateTime(rs, "last_handled_at")),
                args.toArray());
        String[] headers = {"统计日期", "集团编码", "集团名称", "Excel 原始客户经理", "当前有效客户经理",
                "账户 ID", "承载产品名称", "期末欠费金额", "账龄", "工单状态", "最后处理人", "最后处理时间"};
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("欠费工单");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) header.createCell(column).setCellValue(headers[column]);
            for (int index = 0; index < rows.size(); index++) {
                WorkOrderExportRow item = rows.get(index);
                var row = sheet.createRow(index + 1);
                String[] values = {item.statisticsKey(), item.groupCode(), item.groupName(), item.excelManagerName(),
                        item.effectiveManagerName() == null ? "未分配（归属匹配异常）" : item.effectiveManagerName(),
                        item.accountId(), item.carryingProductName(), item.endingArrearsAmount() == null ? null
                        : item.endingArrearsAmount().toPlainString(), item.agingValue(), item.status(),
                        item.lastHandledByName(), item.lastHandledAt() == null ? null : item.lastHandledAt().toString()};
                for (int column = 0; column < values.length; column++) {
                    if (values[column] != null) row.createCell(column).setCellValue(values[column]);
                }
            }
            for (int column = 0; column < headers.length; column++) sheet.autoSizeColumn(column);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("欠费工单报表生成失败", exception);
        }
    }

    private JsonNode readJson(String value) {
        if (value == null || value.isBlank()) return objectMapper.createArrayNode();
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("欠费原始字段无法读取", exception);
        }
    }

    private static LocalDateTime nullableDateTime(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void contains(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null) return;
        where.append(" AND ").append(column).append(" LIKE ?");
        args.add(like(value));
    }

    private static void appendFilters(Filters request, StringBuilder where, List<Object> args, boolean includeWorkOrderStatus) {
        if (request.groupQuery() != null) {
            where.append(" AND (a.group_code LIKE ? OR a.group_name LIKE ?)");
            String pattern = like(request.groupQuery());
            args.add(pattern);
            args.add(pattern);
        }
        contains(where, args, "a.account_id", request.accountId());
        contains(where, args, "a.carrying_product_name", request.carryingProductName());
        if (request.effectiveManagerUserId() != null) {
            where.append(" AND s.effective_manager_user_id=?");
            args.add(request.effectiveManagerUserId());
        }
        if (includeWorkOrderStatus && request.workOrderStatus() != null) {
            where.append(" AND w.status=?");
            args.add(request.workOrderStatus());
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

    private static String like(String value) {
        return "%" + value.strip() + "%";
    }

    private static void validate(Filters filters) {
        if (filters.page() < 1) throw new IllegalArgumentException("page 必须为正数");
        if (!List.of(20, 50, 100).contains(filters.pageSize())) {
            throw new IllegalArgumentException("pageSize 仅支持 20、50 或 100");
        }
        String key = filters.statisticsKey();
        if (key != null) {
            if (!key.matches("[0-9]{6}")) throw new IllegalArgumentException("欠费统计日期应为 yyyyMM");
            try {
                java.time.YearMonth.parse(key, java.time.format.DateTimeFormatter.ofPattern("uuuuMM"));
            } catch (java.time.DateTimeException exception) {
                throw new IllegalArgumentException("欠费统计日期无效");
            }
        }
        validateStatisticsDate(filters.statisticsDateFrom(), "起始统计月份");
        validateStatisticsDate(filters.statisticsDateTo(), "结束统计月份");
        if (filters.statisticsDateFrom() != null && filters.statisticsDateTo() != null
                && filters.statisticsDateFrom().compareTo(filters.statisticsDateTo()) > 0) {
            throw new IllegalArgumentException("起始统计月份不能晚于结束统计月份");
        }
        if (filters.effectiveManagerUserId() != null && filters.effectiveManagerUserId() <= 0) {
            throw new IllegalArgumentException("effectiveManagerUserId 必须为正数");
        }
        if (filters.workOrderStatus() != null && !ArrearsWorkOrderService.STATUSES.contains(filters.workOrderStatus())) {
            throw new IllegalArgumentException("欠费工单状态无效");
        }
    }

    private static void validateStatisticsDate(String key, String label) {
        if (key == null) return;
        if (!key.matches("[0-9]{6}")) throw new IllegalArgumentException(label + "应为 yyyyMM");
        try {
            java.time.YearMonth.parse(key, java.time.format.DateTimeFormatter.ofPattern("uuuuMM"));
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException(label + "无效");
        }
    }

    public record Filters(String statisticsKey, String groupQuery, String accountId,
                          String carryingProductName, Long effectiveManagerUserId,
                          int page, int pageSize, String statisticsDateFrom, String statisticsDateTo,
                          String workOrderStatus) {
        public Filters(String statisticsKey, String groupQuery, String accountId,
                       String carryingProductName, Long effectiveManagerUserId, int page, int pageSize) {
            this(statisticsKey, groupQuery, accountId, carryingProductName, effectiveManagerUserId,
                    page, pageSize, null, null, null);
        }
        public Filters(String statisticsKey, String groupQuery, String accountId,
                       String carryingProductName, Long effectiveManagerUserId, int page, int pageSize,
                       String statisticsDateFrom, String statisticsDateTo) {
            this(statisticsKey, groupQuery, accountId, carryingProductName, effectiveManagerUserId,
                    page, pageSize, statisticsDateFrom, statisticsDateTo, null);
        }

        public static Filters defaults() {
            return new Filters(null, null, null, null, null, 1, 20, null, null, null);
        }

        private Filters withDefaults(YearMonth currentMonth) {
            boolean currentMonthDefault = statisticsKey == null
                    && (statisticsDateFrom == null || statisticsDateFrom.isBlank())
                    && (statisticsDateTo == null || statisticsDateTo.isBlank());
            return new Filters(statisticsKey, clean(groupQuery), clean(accountId), clean(carryingProductName),
                    effectiveManagerUserId, page == 0 ? 1 : page, pageSize == 0 ? 20 : pageSize,
                    currentMonthDefault ? currentMonth.format(java.time.format.DateTimeFormatter.ofPattern("uuuuMM"))
                            : clean(statisticsDateFrom),
                    currentMonthDefault ? currentMonth.format(java.time.format.DateTimeFormatter.ofPattern("uuuuMM"))
                            : clean(statisticsDateTo), clean(workOrderStatus));
        }

        private static String clean(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    public record Item(long snapshotRecordId, String statisticsKey, String groupCode, String groupName,
                       String excelManagerName, Long effectiveManagerUserId, String effectiveManagerName,
                       String accountId, String carryingProductName, BigDecimal endingArrearsAmount,
                       String agingValue, Long workOrderId, String workOrderStatus,
                       Long lastHandledByUserId, String lastHandledByName,
                       @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
                       LocalDateTime lastHandledAt) {
        public Item(long snapshotRecordId, String statisticsKey, String groupCode, String groupName,
                    String excelManagerName, Long effectiveManagerUserId, String effectiveManagerName,
                    String accountId, String carryingProductName, BigDecimal endingArrearsAmount,
                    String agingValue) {
            this(snapshotRecordId, statisticsKey, groupCode, groupName, excelManagerName,
                    effectiveManagerUserId, effectiveManagerName, accountId, carryingProductName,
                    endingArrearsAmount, agingValue, null, "待处理", null, null, null);
        }
    }

    public record Detail(Item item, long batchId, String originalFilename, String sheetName,
                         int sourceRowNumber, String identityKey, JsonNode rawFields,
                         JsonNode normalizedFields, String assignmentStatus,
                         List<ArrearsWorkOrderService.TimelineEvent> handlingTimeline) {
        public Detail(Item item, long batchId, String originalFilename, String sheetName,
                      int sourceRowNumber, String identityKey, JsonNode rawFields,
                      JsonNode normalizedFields, String assignmentStatus) {
            this(item, batchId, originalFilename, sheetName, sourceRowNumber, identityKey,
                    rawFields, normalizedFields, assignmentStatus, List.of());
        }
        public Detail { handlingTimeline = List.copyOf(handlingTimeline); }
    }

    private record DetailRow(long snapshotRecordId, String statisticsKey, long batchId,
                             String originalFilename, String excelManagerName, Long effectiveManagerUserId,
                             String effectiveManagerName, String sheetName, int sourceRowNumber,
                             String identityKey, JsonNode rawFields, JsonNode normalizedFields,
                             String groupCode, String groupName, String accountId, String carryingProductName,
                             BigDecimal endingArrearsAmount, String agingValue, String exceptionStatus,
                             Long workOrderId, String workOrderStatus, Long lastHandledByUserId,
                             String lastHandledByName, LocalDateTime lastHandledAt) {}

    private record ExportRow(JsonNode rawFields, String effectiveManagerName, long batchId,
                             String originalFilename, String sheetName, int sourceRowNumber,
                             String fieldProvenance) {}

    private record WorkOrderExportRow(String statisticsKey, String groupCode, String groupName,
                                      String excelManagerName, String effectiveManagerName,
                                      String accountId, String carryingProductName,
                                      BigDecimal endingArrearsAmount, String agingValue, String status,
                                      String lastHandledByName, LocalDateTime lastHandledAt) {}

    public record Page(List<Item> items, long total, int page, int pageSize,
                       String statisticsKey, List<String> availableStatisticsKeys) {
        public Page {
            items = List.copyOf(items);
            availableStatisticsKeys = List.copyOf(availableStatisticsKeys);
        }
    }
}
