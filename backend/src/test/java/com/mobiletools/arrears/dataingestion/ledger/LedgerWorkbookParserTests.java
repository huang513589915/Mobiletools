package com.mobiletools.arrears.dataingestion.ledger;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerWorkbookParserTests {

    private final LedgerWorkbookParser parser = new LedgerWorkbookParser();

    @Test
    void suppliedArrearsWorkbookMatchesFrozenContract() throws Exception {
        byte[] bytes = supplied("9月集团账户欠费明细月表.xlsx");

        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.ARREARS);

        // The real sample contains one blank optional “承载产品名称” cell.
        // Optional source fields remain available in raw fields but do not block
        // the batch, and the normalized field is omitted when the value is blank.
        assertFalse(result.blocking(), result.issues().toString());
        assertTrue(result.rows().get(1).issues().isEmpty());
        assertFalse(result.rows().get(1).normalizedFields().containsKey("承载产品名称"));
        assertEquals("202609", result.statisticsKey());
        assertEquals(91, result.headers().size());
        assertEquals(List.of(3, 5), result.headers().stream()
                .filter(header -> header.header().equals("集团编码"))
                .map(LedgerWorkbookParser.HeaderColumn::columnIndex).toList());
        assertEquals(3, result.rows().size());
        assertEquals("202609:3234242342449516", result.rows().get(0).identityKey());
        assertEquals("Sheet1", result.rows().get(0).sheetName());
        assertEquals(2, result.rows().get(0).rowNumber());
        assertTrue(result.rows().get(0).rawFields().stream()
                .noneMatch(field -> field.header().isBlank()));
        assertEquals(2, result.rows().get(0).rawFields().stream()
                .filter(field -> field.header().equals("集团编码")).count());
    }

    @Test
    void suppliedLineWorkbookMatchesFrozenContract() throws Exception {
        byte[] bytes = supplied("10月底到期需续签明细.xlsx");

        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.LINE_EXPIRY);

        assertFalse(result.blocking(), result.issues().toString());
        assertEquals("20260901", result.statisticsKey());
        assertEquals(58, result.headers().size());
        assertEquals(5, result.rows().size());
        assertEquals("20260901:326KKKC5221", result.rows().get(0).identityKey());
        assertEquals("2026-10-31", result.rows().get(0).normalizedFields()
                .get("计费截止日期").toString());
    }

    @Test
    void requiredValueAndDuplicateIdentityReportOriginalRows() throws Exception {
        byte[] bytes = workbook(
                new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "承载产品名称", "期末欠费金额", "账龄"},
                new String[]{"202609", "区县一", "G-1", "集团一", "G-1", "张伟", "A-1", "产品一", "1.20", "3"},
                new String[]{"202609", "区县二", "G-2", "集团二", "G-2", "张伟", "A-1", "产品二", "2.30", "4"},
                new String[]{"202609", "区县三", "G-3", "集团三", "G-3", "", "A-3", "产品三", "3.40", "5"}
        );

        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.ARREARS);

        assertTrue(result.blocking());
        assertTrue(result.rows().get(1).issues().stream().anyMatch(issue ->
                issue.code().equals("DUPLICATE_SNAPSHOT_IDENTITY") && issue.rowNumber() == 3
                        && issue.sheetName().equals("Sheet1")));
        assertTrue(result.rows().get(2).issues().stream().anyMatch(issue ->
                issue.code().equals("REQUIRED_VALUE_MISSING") && issue.rowNumber() == 4
                        && issue.sheetName().equals("Sheet1")));
    }

    @Test
    void invalidLineDateAndMissingHeaderAreBlockingWithLocation() throws Exception {
        byte[] bytes = workbook(
                new String[]{"统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码", "客户经理姓名", "计费截止日期"},
                new String[]{"20261301", "G-1", "集团一", "P-1", "产品一", "S-1", "张伟", "20261031"}
        );

        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.LINE_EXPIRY);

        assertTrue(result.blocking());
        assertTrue(result.rows().get(0).issues().stream().anyMatch(issue ->
                issue.code().equals("STATISTICS_DATE_INVALID") && issue.rowNumber() == 2
                        && issue.sheetName().equals("Sheet1")));

        byte[] missingHeader = workbook(
                new String[]{"统计日期", "集团编码", "集团名称", "产品编码", "产品名称", "成员服务号码", "客户经理姓名"},
                new String[]{"20260901", "G-1", "集团一", "P-1", "产品一", "S-1", "张伟"}
        );
        LedgerWorkbookParser.ParseResult missing = parser.parse(missingHeader, LedgerWorkbookParser.SourceType.LINE_EXPIRY);
        assertTrue(missing.blocking());
        assertTrue(missing.issues().stream().anyMatch(issue ->
                issue.code().equals("REQUIRED_HEADER_MISSING") && issue.rowNumber() == 1
                        && issue.sheetName().equals("Sheet1")));
    }

    @Test
    void emptyHeadersAreIgnoredAndDuplicateArrearsHeaderPositionsAreRequired() throws Exception {
        byte[] bytes = workbook(
                new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "承载产品名称", "期末欠费金额", "账龄", ""},
                new String[]{"202609", "区县一", "G-1", "集团一", "G-1", "张伟", "A-1", "产品一", "0", "0", "忽略"}
        );
        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.ARREARS);

        assertFalse(result.blocking(), result.issues().toString());
        assertEquals(10, result.headers().size());
        assertTrue(result.headers().stream().noneMatch(header -> header.columnIndex() == 11));
        assertEquals("G-1", result.rows().get(0).normalizedFields().get("集团编码"));
    }

    @Test
    void optionalFieldsMayBeAbsentOrBlankForBothSources() throws Exception {
        byte[] arrears = workbook(
                new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "期末欠费金额"},
                new String[]{"202609", "区县一", "G-1", "集团一", "G-1", "张伟", "A-1", "0"}
        );
        LedgerWorkbookParser.ParseResult arrearsResult = parser.parse(arrears, LedgerWorkbookParser.SourceType.ARREARS);
        assertFalse(arrearsResult.blocking(), arrearsResult.issues().toString());
        assertFalse(arrearsResult.rows().get(0).normalizedFields().containsKey("承载产品名称"));
        assertFalse(arrearsResult.rows().get(0).normalizedFields().containsKey("账龄"));

        byte[] line = workbook(
                new String[]{"统计日期", "集团编码", "集团名称", "成员服务号码", "客户经理姓名", "计费截止日期", "产品编码", "产品名称"},
                new String[]{"20260901", "G-1", "集团一", "S-1", "张伟", "20261031", "", ""}
        );
        LedgerWorkbookParser.ParseResult lineResult = parser.parse(line, LedgerWorkbookParser.SourceType.LINE_EXPIRY);
        assertFalse(lineResult.blocking(), lineResult.issues().toString());
        assertFalse(lineResult.rows().get(0).normalizedFields().containsKey("产品编码"));
        assertFalse(lineResult.rows().get(0).normalizedFields().containsKey("产品名称"));
    }

    @Test
    void parserKeepsPresentBlankHeadersAndIgnoresEntirelyBlankHeaders() throws Exception {
        byte[] bytes = workbook(
                new String[]{"日期", "区县", "集团编码", "集团名称", "集团编码", "集团客户经理姓名", "账户ID", "期末欠费金额", "账龄", ""},
                new String[]{"202609", "区县一", "G-1", "集团一", "G-1", "张伟", "A-1", "1", "", "ignored"}
        );

        LedgerWorkbookParser.ParseResult result = parser.parse(bytes, LedgerWorkbookParser.SourceType.ARREARS);

        assertFalse(result.blocking(), result.issues().toString());
        assertFalse(result.rows().get(0).normalizedFields().containsKey("账龄"));
        assertTrue(result.rows().get(0).rawFields().stream().anyMatch(field ->
                field.header().equals("账龄") && field.value().isEmpty()));
        assertTrue(result.rows().get(0).rawFields().stream().noneMatch(field -> field.header().isBlank()));
    }

    private byte[] supplied(String fileName) throws Exception {
        Path path = Path.of("..", "excel", fileName).toAbsolutePath().normalize();
        assertTrue(Files.exists(path), "Missing supplied workbook: " + path);
        return Files.readAllBytes(path);
    }

    private byte[] workbook(String[] headers, String[]... rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            var headerRow = sheet.createRow(0);
            for (int column = 0; column < headers.length; column++) {
                headerRow.createCell(column).setCellValue(headers[column]);
            }
            for (int row = 0; row < rows.length; row++) {
                var dataRow = sheet.createRow(row + 1);
                for (int column = 0; column < rows[row].length; column++) {
                    dataRow.createCell(column).setCellValue(rows[row][column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
