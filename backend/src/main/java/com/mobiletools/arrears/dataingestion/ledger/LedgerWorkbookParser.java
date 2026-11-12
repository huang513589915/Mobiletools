package com.mobiletools.arrears.dataingestion.ledger;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses the two independently maintained source workbooks without persisting data. */
@Component
public class LedgerWorkbookParser {

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("uuuuMM");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    public ParseResult parse(byte[] bytes, SourceType sourceType) {
        if (sourceType == null) {
            return failed(null, "SOURCE_TYPE_REQUIRED", "数据来源不能为空");
        }
        if (bytes == null || bytes.length == 0) {
            return failed(sourceType, "WORKBOOK_EMPTY", "上传文件为空或未收到完整文件");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            return parseWorkbook(workbook, sourceType);
        } catch (Exception exception) {
            return failed(sourceType, "WORKBOOK_UNREADABLE", "无法解析工作簿：" + safeMessage(exception));
        }
    }

    private ParseResult parseWorkbook(Workbook workbook, SourceType sourceType) {
        if (workbook.getNumberOfSheets() == 0) {
            return failed(sourceType, "WORKBOOK_NO_SHEETS", "工作簿不包含工作表");
        }

        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        List<SheetHeaders> candidates = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) continue;
            List<HeaderColumn> columns = readHeaders(headerRow, formatter, evaluator);
            if (columns.stream().anyMatch(column -> sourceType.requiredHeaders().contains(column.header()))) {
                candidates.add(new SheetHeaders(sheet, columns));
            }
        }

        if (candidates.isEmpty()) {
            String firstSheet = workbook.getSheetAt(0).getSheetName();
            List<ValidationIssue> issues = List.of(issue("REQUIRED_HEADERS_MISSING", Severity.BLOCKING,
                    "第 1 行未找到该数据来源的必需表头", firstSheet, 1, null));
            return new ParseResult(sourceType, null, List.of(), List.of(), issues);
        }
        if (candidates.size() > 1) {
            List<ValidationIssue> issues = new ArrayList<>();
            for (SheetHeaders candidate : candidates) {
                issues.add(issue("MULTIPLE_DATA_SHEETS", Severity.BLOCKING,
                        "多个工作表包含该数据来源的字段，无法确定唯一数据工作表", candidate.sheet().getSheetName(), 1, null));
            }
            return new ParseResult(sourceType, null, candidates.get(0).columns(), List.of(), issues);
        }

        SheetHeaders candidate = candidates.get(0);
        List<ValidationIssue> workbookIssues = validateHeaders(sourceType, candidate);
        if (hasBlocking(workbookIssues)) {
            return new ParseResult(sourceType, null, candidate.columns(), List.of(), workbookIssues);
        }

        Map<String, Integer> fieldColumns = resolveFieldColumns(sourceType, candidate.columns());
        List<ParsedRow> rows = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        String batchStatisticsKey = null;
        Sheet sheet = candidate.sheet();

        for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isBlankRow(row)) continue;

            List<ValidationIssue> rowIssues = new ArrayList<>();
            List<RawField> rawFields = readRawFields(row, candidate.columns(), formatter, evaluator);
            Map<String, Object> normalized = new LinkedHashMap<>();
            String statisticsKey = parseStatisticsKey(sourceType, row, fieldColumns, formatter, evaluator,
                    sheet.getSheetName(), rowIndex + 1, rowIssues);
            if (statisticsKey != null) {
                if (batchStatisticsKey == null) batchStatisticsKey = statisticsKey;
                else if (!batchStatisticsKey.equals(statisticsKey)) {
                    rowIssues.add(issue("MIXED_STATISTICS_DATES", Severity.BLOCKING,
                            "同一工作簿包含多个统计日期", sheet.getSheetName(), rowIndex + 1,
                            fieldColumns.get(sourceType.statisticsHeader())));
                }
                normalized.put(sourceType.statisticsHeader(), statisticsKey);
            }

            String identity = parseIdentifier(sourceType.identityHeader(), row, fieldColumns,
                    formatter, evaluator, sheet.getSheetName(), rowIndex + 1, rowIssues);
            if (identity != null) normalized.put(sourceType.identityHeader(), identity);

            String groupCode = parseIdentifier("集团编码", row, fieldColumns, formatter, evaluator,
                    sheet.getSheetName(), rowIndex + 1, rowIssues);
            if (groupCode != null) normalized.put("集团编码", groupCode);

            for (String header : sourceType.requiredTextHeaders()) {
                if (header.equals("集团编码") || header.equals(sourceType.statisticsHeader())
                        || header.equals(sourceType.identityHeader())) continue;
                String value = readText(row, fieldColumns.get(header), formatter, evaluator);
                if (isBlank(value)) {
                    rowIssues.add(issue("REQUIRED_VALUE_MISSING", Severity.BLOCKING,
                            "必填字段“" + header + "”为空", sheet.getSheetName(), rowIndex + 1,
                            fieldColumns.get(header)));
                } else {
                    normalized.put(header, value);
                }
            }

            String managerHeader = sourceType.managerHeader();
            String managerName = readText(row, fieldColumns.get(managerHeader), formatter, evaluator);
            if (isBlank(managerName)) {
                rowIssues.add(issue("REQUIRED_VALUE_MISSING", Severity.BLOCKING,
                        "必填字段“" + managerHeader + "”为空", sheet.getSheetName(), rowIndex + 1,
                        fieldColumns.get(managerHeader)));
            } else {
                normalized.put(managerHeader, managerName);
            }

            if (sourceType == SourceType.ARREARS) {
                parseAmount(row, fieldColumns.get("期末欠费金额"), formatter, evaluator,
                        sheet.getSheetName(), rowIndex + 1, rowIssues, normalized);
                putOptionalText(row, fieldColumns.get("承载产品名称"), formatter, evaluator,
                        normalized, "承载产品名称");
                putOptionalText(row, fieldColumns.get("账龄"), formatter, evaluator,
                        normalized, "账龄");
            } else {
                putOptionalText(row, fieldColumns.get("产品编码"), formatter, evaluator,
                        normalized, "产品编码");
                putOptionalText(row, fieldColumns.get("产品名称"), formatter, evaluator,
                        normalized, "产品名称");
                LocalDate cutoffDate = parseRequiredDate(row, fieldColumns.get("计费截止日期"), formatter, evaluator,
                        sheet.getSheetName(), rowIndex + 1, "计费截止日期", rowIssues);
                if (cutoffDate != null) normalized.put("计费截止日期", cutoffDate);
            }

            String identityKey = statisticsKey == null || identity == null
                    ? null : statisticsKey + ":" + identity;
            if (identityKey != null && !identities.add(identityKey)) {
                rowIssues.add(issue("DUPLICATE_SNAPSHOT_IDENTITY", Severity.BLOCKING,
                        "同一统计日期内的业务键重复：" + identity, sheet.getSheetName(), rowIndex + 1,
                        fieldColumns.get(sourceType.identityHeader())));
            }

            rows.add(new ParsedRow(sheet.getSheetName(), rowIndex + 1, statisticsKey,
                    identityKey, managerName, normalized, rawFields, rowIssues));
        }

        if (rows.isEmpty()) {
            workbookIssues.add(issue("NO_DATA_ROWS", Severity.BLOCKING,
                    "工作表没有可导入的数据行", sheet.getSheetName(), null, null));
        }
        return new ParseResult(sourceType, batchStatisticsKey, candidate.columns(), rows, workbookIssues);
    }

    private List<ValidationIssue> validateHeaders(SourceType sourceType, SheetHeaders candidate) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        for (HeaderColumn column : candidate.columns()) {
            positions.computeIfAbsent(column.header(), ignored -> new ArrayList<>()).add(column.columnIndex());
        }
        for (String required : sourceType.requiredHeaders()) {
            List<Integer> found = positions.getOrDefault(required, List.of());
            if (found.isEmpty()) {
                issues.add(issue("REQUIRED_HEADER_MISSING", Severity.BLOCKING,
                        "缺少必填表头“" + required + "”", candidate.sheet().getSheetName(), 1, null));
            } else if (required.equals("集团编码") && sourceType == SourceType.ARREARS) {
                if (!found.equals(List.of(3, 5))) {
                    issues.add(issue("DUPLICATE_HEADER_POSITION_INVALID", Severity.BLOCKING,
                            "欠费表“集团编码”必须位于第 3、5 列；当前位于" + found, candidate.sheet().getSheetName(), 1, null));
                }
            } else if (found.size() > 1) {
                issues.add(issue("REQUIRED_HEADER_AMBIGUOUS", Severity.BLOCKING,
                        "必填表头“" + required + "”出现多次，无法确定规范化字段列", candidate.sheet().getSheetName(), 1, null));
            }
        }
        return issues;
    }

    private Map<String, Integer> resolveFieldColumns(SourceType sourceType, List<HeaderColumn> columns) {
        Map<String, Integer> fieldColumns = new LinkedHashMap<>();
        for (HeaderColumn column : columns) {
            if (!fieldColumns.containsKey(column.header())) fieldColumns.put(column.header(), column.columnIndex());
        }
        if (sourceType == SourceType.ARREARS) fieldColumns.put("集团编码", 3);
        return fieldColumns;
    }

    private String parseStatisticsKey(SourceType sourceType, Row row, Map<String, Integer> columns,
                                      DataFormatter formatter, FormulaEvaluator evaluator, String sheet,
                                      int rowNumber, List<ValidationIssue> issues) {
        Integer column = columns.get(sourceType.statisticsHeader());
        Cell cell = column == null ? null : row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (sourceType == SourceType.ARREARS) {
            String token = numericOrTextToken(cell, formatter, evaluator);
            if (token == null || !token.matches("\\d{6}")) {
                issues.add(issue("STATISTICS_DATE_INVALID", Severity.BLOCKING,
                        "日期必须为有效的 yyyyMM 月份", sheet, rowNumber, column));
                return null;
            }
            try {
                return MONTH_FORMAT.format(java.time.YearMonth.parse(token, MONTH_FORMAT));
            } catch (DateTimeParseException exception) {
                issues.add(issue("STATISTICS_DATE_INVALID", Severity.BLOCKING,
                        "日期必须为有效的 yyyyMM 月份", sheet, rowNumber, column));
                return null;
            }
        }
        LocalDate parsed = parseDate(cell, formatter, evaluator);
        if (parsed == null) {
            issues.add(issue("STATISTICS_DATE_INVALID", Severity.BLOCKING,
                    "统计日期必须为有效的 yyyyMMdd 日期", sheet, rowNumber, column));
            return null;
        }
        return DATE_FORMAT.format(parsed);
    }

    private String parseIdentifier(String header, Row row, Map<String, Integer> columns,
                                    DataFormatter formatter, FormulaEvaluator evaluator, String sheet,
                                    int rowNumber, List<ValidationIssue> issues) {
        Integer column = columns.get(header);
        Cell cell = column == null ? null : row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        String value = identifierValue(cell, formatter, evaluator);
        if (isBlank(value)) {
            issues.add(issue("REQUIRED_VALUE_MISSING", Severity.BLOCKING,
                    "必填字段“" + header + "”为空", sheet, rowNumber, column));
            return null;
        }
        return value;
    }

    private void parseAmount(Row row, Integer column, DataFormatter formatter, FormulaEvaluator evaluator,
                             String sheet, int rowNumber, List<ValidationIssue> issues,
                             Map<String, Object> normalized) {
        Cell cell = column == null ? null : row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        String token = numericOrTextToken(cell, formatter, evaluator);
        if (token == null || !DECIMAL.matcher(token).matches()) {
            issues.add(issue("NUMBER_INVALID", Severity.BLOCKING,
                    "期末欠费金额必须为有效十进制数值", sheet, rowNumber, column));
            return;
        }
        try {
            normalized.put("期末欠费金额", new BigDecimal(token));
        } catch (NumberFormatException exception) {
            issues.add(issue("NUMBER_INVALID", Severity.BLOCKING,
                    "期末欠费金额必须为有效十进制数值", sheet, rowNumber, column));
        }
    }

    private void putOptionalText(Row row, Integer column, DataFormatter formatter,
                                 FormulaEvaluator evaluator, Map<String, Object> normalized,
                                 String header) {
        String value = readText(row, column, formatter, evaluator);
        if (!isBlank(value)) normalized.put(header, value);
    }

    private LocalDate parseRequiredDate(Row row, Integer column, DataFormatter formatter,
                                        FormulaEvaluator evaluator, String sheet, int rowNumber,
                                        String header, List<ValidationIssue> issues) {
        Cell cell = column == null ? null : row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        LocalDate date = parseDate(cell, formatter, evaluator);
        if (date == null) {
            issues.add(issue("DATE_INVALID", Severity.BLOCKING,
                    "字段“" + header + "”必须为有效的 yyyyMMdd 日期", sheet, rowNumber, column));
        }
        return date;
    }

    private LocalDate parseDate(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            try {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        String token = numericOrTextToken(cell, formatter, evaluator);
        if (token == null || !token.matches("\\d{8}")) return null;
        try {
            return LocalDate.parse(token, DATE_FORMAT);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String identifierValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            BigDecimal numeric = numericCellValue(cell);
            if (numeric == null || numeric.stripTrailingZeros().scale() > 0) return null;
            return numeric.stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == CellType.FORMULA) {
            String formulaResult = evaluatedText(cell, formatter, evaluator);
            if (formulaResult == null || formulaResult.isBlank()) return null;
            if (formulaResult.matches("[+-]?\\d+(?:\\.0+)?")) {
                try {
                    return new BigDecimal(formulaResult).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return formulaResult;
        }
        return readText(cell, formatter, evaluator);
    }

    private String numericOrTextToken(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.NUMERIC) {
            BigDecimal numeric = numericCellValue(cell);
            return numeric == null ? null : numeric.stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == CellType.FORMULA) return evaluatedText(cell, formatter, evaluator);
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().strip();
        return null;
    }

    private BigDecimal numericCellValue(Cell cell) {
        try {
            if (cell instanceof XSSFCell xssfCell && xssfCell.getRawValue() != null) {
                return new BigDecimal(xssfCell.getRawValue());
            }
            return BigDecimal.valueOf(cell.getNumericCellValue());
        } catch (NumberFormatException | IllegalStateException exception) {
            return null;
        }
    }

    private String readText(Row row, Integer column, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (column == null) return null;
        return readText(row.getCell(column - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL), formatter, evaluator);
    }

    private String readText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) return null;
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue();
        if (cell.getCellType() == CellType.NUMERIC) {
            BigDecimal numeric = numericCellValue(cell);
            return numeric == null ? null : numeric.stripTrailingZeros().toPlainString();
        }
        if (cell.getCellType() == CellType.FORMULA) return evaluatedText(cell, formatter, evaluator);
        if (cell.getCellType() == CellType.BOOLEAN) return Boolean.toString(cell.getBooleanCellValue());
        if (cell.getCellType() == CellType.ERROR) return FormulaErrorText.from(cell.getErrorCellValue());
        return null;
    }

    private String evaluatedText(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            return formatter.formatCellValue(cell, evaluator);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private List<HeaderColumn> readHeaders(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        List<HeaderColumn> result = new ArrayList<>();
        short last = row.getLastCellNum();
        for (int index = 0; index < last; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String header = readText(cell, formatter, evaluator);
            if (header == null || header.strip().isEmpty()) continue;
            result.add(new HeaderColumn(index + 1, header.strip()));
        }
        return result;
    }

    private List<RawField> readRawFields(Row row, List<HeaderColumn> columns,
                                         DataFormatter formatter, FormulaEvaluator evaluator) {
        List<RawField> fields = new ArrayList<>();
        for (HeaderColumn column : columns) {
            Cell cell = row.getCell(column.columnIndex() - 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            String value = readText(cell, formatter, evaluator);
            String cellType = cell == null ? "BLANK" : cell.getCellType().name();
            String formula = cell != null && cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : null;
            fields.add(new RawField(column.columnIndex(), column.header(), cellType, value, formula));
        }
        return fields;
    }

    private boolean isBlankRow(Row row) {
        short last = row.getLastCellNum();
        for (int index = 0; index < last; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (cell == null || cell.getCellType() == CellType.BLANK) continue;
            if (cell.getCellType() == CellType.STRING && cell.getStringCellValue().isBlank()) continue;
            return false;
        }
        return true;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasBlocking(List<ValidationIssue> issues) {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKING);
    }

    private static ValidationIssue issue(String code, Severity severity, String message,
                                         String sheet, Integer row, Integer column) {
        return new ValidationIssue(code, severity, message, sheet, row, column);
    }

    private static ParseResult failed(SourceType sourceType, String code, String message) {
        return new ParseResult(sourceType, null, List.of(), List.of(),
                List.of(issue(code, Severity.BLOCKING, message, null, null, null)));
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) return exception.getClass().getSimpleName();
        return message.length() > 200 ? message.substring(0, 200) : message;
    }

    private record SheetHeaders(Sheet sheet, List<HeaderColumn> columns) {}

    public enum SourceType {
        ARREARS("日期", "账户ID", "集团客户经理姓名",
                List.of("日期", "集团编码", "集团名称", "集团客户经理姓名", "账户ID", "期末欠费金额"),
                List.of("集团名称")),
        LINE_EXPIRY("统计日期", "成员服务号码", "客户经理姓名",
                List.of("统计日期", "集团编码", "集团名称", "成员服务号码", "客户经理姓名", "计费截止日期"),
                List.of("集团名称"));

        private final String statisticsHeader;
        private final String identityHeader;
        private final String managerHeader;
        private final List<String> requiredHeaders;
        private final List<String> requiredTextHeaders;

        SourceType(String statisticsHeader, String identityHeader, String managerHeader,
                   List<String> requiredHeaders, List<String> requiredTextHeaders) {
            this.statisticsHeader = statisticsHeader;
            this.identityHeader = identityHeader;
            this.managerHeader = managerHeader;
            this.requiredHeaders = Collections.unmodifiableList(requiredHeaders);
            this.requiredTextHeaders = Collections.unmodifiableList(requiredTextHeaders);
        }

        public String statisticsHeader() { return statisticsHeader; }
        public String identityHeader() { return identityHeader; }
        public String managerHeader() { return managerHeader; }
        public List<String> requiredHeaders() { return requiredHeaders; }
        public List<String> requiredTextHeaders() { return requiredTextHeaders; }
    }

    public enum Severity { BLOCKING, WARNING }

    public record HeaderColumn(int columnIndex, String header) {}

    public record RawField(int columnIndex, String header, String cellType, String value, String formula) {}

    public record ValidationIssue(String code, Severity severity, String message,
                                 String sheetName, Integer rowNumber, Integer columnIndex) {}

    public record ParsedRow(String sheetName, int rowNumber, String statisticsKey,
                            String identityKey, String excelManagerName,
                            Map<String, Object> normalizedFields, List<RawField> rawFields,
                            List<ValidationIssue> issues) {
        public ParsedRow {
            normalizedFields = Collections.unmodifiableMap(new LinkedHashMap<>(normalizedFields));
            rawFields = List.copyOf(rawFields);
            issues = List.copyOf(issues);
        }
        public boolean blocking() { return issues.stream().anyMatch(issue -> issue.severity() == Severity.BLOCKING); }
    }

    public record ParseResult(SourceType sourceType, String statisticsKey,
                              List<HeaderColumn> headers, List<ParsedRow> rows,
                              List<ValidationIssue> issues) {
        public ParseResult {
            headers = List.copyOf(headers);
            rows = List.copyOf(rows);
            issues = List.copyOf(issues);
        }
        public boolean blocking() {
            return hasBlocking(issues) || rows.stream().anyMatch(ParsedRow::blocking);
        }
        public long warningCount() {
            return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count()
                    + rows.stream().flatMap(row -> row.issues().stream())
                    .filter(issue -> issue.severity() == Severity.WARNING).count();
        }
    }

    private static final class FormulaErrorText {
        private static String from(byte errorCode) {
            return "#ERROR_" + Byte.toUnsignedInt(errorCode);
        }
    }
}
