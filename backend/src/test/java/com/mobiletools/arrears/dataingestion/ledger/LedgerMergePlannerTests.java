package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LedgerMergePlannerTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LedgerMergePlanner planner = new LedgerMergePlanner(mapper);

    @Test
    void presentBlankOptionalHeaderClearsValueAndReportsTheClear() throws Exception {
        LedgerMergePlanner.Current old = current("202610:A-1",
                """
                {"日期":"202610","账户ID":"A-1","集团编码":"G-1","集团名称":"客户一",
                 "集团客户经理姓名":"张三","期末欠费金额":100,"承载产品名称":"专线产品"}
                """,
                """
                [{"columnIndex":1,"header":"账户ID","cellType":"STRING","value":"A-1"},
                 {"columnIndex":2,"header":"承载产品名称","cellType":"STRING","value":"专线产品"},
                 {"columnIndex":3,"header":"期末欠费金额","cellType":"NUMERIC","value":"100.0"}]
                """);
        LedgerMergePlanner.Candidate uploaded = candidate("202610:A-1",
                """
                {"日期":"202610","账户ID":"A-1","集团编码":"G-1","集团名称":"客户一",
                 "集团客户经理姓名":"张三","期末欠费金额":125}
                """,
                """
                [{"columnIndex":1,"header":"期末欠费金额","cellType":"NUMERIC","value":"125.00"},
                 {"columnIndex":2,"header":"账户ID","cellType":"STRING","value":"A-1"},
                 {"columnIndex":3,"header":"承载产品名称","cellType":"BLANK","value":null}]
                """);

        var result = planner.plan("ARREARS", List.of(uploaded), List.of(old), 88);

        assertEquals(1, result.updatedRows());
        var row = result.uploadedRows().get(0);
        assertTrue(row.effectiveNormalizedFields().get("承载产品名称").isNull());
        assertTrue(row.changedFields().toString().contains("承载产品名称"));
        assertTrue(row.changedFields().toString().contains("\"cleared\":true"));
        assertTrue(row.changedFields().toString().contains("125"));
        assertEquals(3, row.effectiveRawFields().get(1).get("columnIndex").asInt());
        assertEquals(88, row.effectiveProvenance().get("承载产品名称#1").get("batchId").asLong());
        assertEquals("sheet", row.effectiveProvenance().get("承载产品名称#1").get("sheetName").asText());
    }

    @Test
    void absentOptionalColumnPreservesItsEffectiveValueAndColumnOrderDoesNotMatter() throws Exception {
        LedgerMergePlanner.Current old = current("202610:A-1",
                """
                {"日期":"202610","账户ID":"A-1","集团编码":"G-1","集团名称":"客户一",
                 "集团客户经理姓名":"张三","期末欠费金额":100,"承载产品名称":"专线产品","账龄":"30天"}
                """,
                """
                [{"columnIndex":1,"header":"账户ID","cellType":"STRING","value":"A-1"},
                 {"columnIndex":2,"header":"期末欠费金额","cellType":"NUMERIC","value":"100.00"},
                 {"columnIndex":3,"header":"承载产品名称","cellType":"STRING","value":"专线产品"},
                 {"columnIndex":10,"header":"账龄","cellType":"STRING","value":"30天"}]
                """, mapper.readTree("{\"账龄#1\":{\"batchId\":10,\"sheetName\":\"old\",\"sourceRowNumber\":4}}"));
        LedgerMergePlanner.Candidate uploaded = candidate("202610:A-1",
                """
                {"日期":"202610","账户ID":"A-1","集团编码":"G-1","集团名称":"客户一",
                 "集团客户经理姓名":"张三","期末欠费金额":100.0}
                """,
                """
                [{"columnIndex":1,"header":"期末欠费金额","cellType":"NUMERIC","value":"100.0"},
                 {"columnIndex":2,"header":"账户ID","cellType":"STRING","value":"A-1"}]
                """);

        var result = planner.plan("ARREARS", List.of(uploaded), List.of(old), 88);

        assertEquals(1, result.unchangedRows());
        assertEquals("专线产品", result.uploadedRows().get(0)
                .effectiveNormalizedFields().get("承载产品名称").asText());
        assertEquals("30天", result.uploadedRows().get(0)
                .effectiveNormalizedFields().get("账龄").asText());
        assertEquals(10, result.uploadedRows().get(0)
                .effectiveProvenance().get("账龄#1").get("batchId").asInt());
        assertFalse(result.semanticNoOp() == false);
    }

    @Test
    void duplicateHeadersAreDistinguishedByOccurrenceAndOmittedCurrentRowsAreRetained() throws Exception {
        LedgerMergePlanner.Current old = current("202610:A-1", "{\"集团编码\":\"G-1\"}",
                """
                [{"columnIndex":3,"header":"集团编码","cellType":"STRING","value":"G-1"},
                 {"columnIndex":5,"header":"集团编码","cellType":"STRING","value":"G-1"}]
                """);
        LedgerMergePlanner.Current omitted = current("202610:A-2", "{}", "[]");
        LedgerMergePlanner.Candidate uploaded = candidate("202610:A-1", "{\"集团编码\":\"G-1\"}",
                """
                [{"columnIndex":3,"header":"集团编码","cellType":"STRING","value":"G-1"},
                 {"columnIndex":5,"header":"集团编码","cellType":"STRING","value":"G-2"}]
                """);

        var result = planner.plan("ARREARS", List.of(uploaded), List.of(old, omitted), 88);

        assertEquals(1, result.updatedRows());
        assertEquals(1, result.retainedCount());
        assertEquals(1, result.uploadedRows().get(0).changedFields().size());
        assertEquals(2, result.uploadedRows().get(0).changedFields().get(0).get("occurrence").asInt());
        assertEquals(3, result.uploadedRows().get(0).effectiveRawFields().get(0).get("columnIndex").asInt());
        assertEquals(5, result.uploadedRows().get(0).effectiveRawFields().get(1).get("columnIndex").asInt());
        assertEquals(88, result.uploadedRows().get(0).effectiveProvenance()
                .get("集团编码#2").get("batchId").asLong());
    }

    @Test
    void blankNewOptionalHeaderClearsAnUnmodeledValueWithoutLosingItsSourcePosition() throws Exception {
        LedgerMergePlanner.Current old = current("202610:A-1", "{\"账户ID\":\"A-1\",\"计费区域\":\"华东\"}",
                """
                [{"columnIndex":1,"header":"账户ID","cellType":"STRING","value":"A-1"},
                 {"columnIndex":12,"header":"计费区域","cellType":"STRING","value":"华东"}]
                """);
        LedgerMergePlanner.Candidate uploaded = candidate("202610:A-1", "{\"账户ID\":\"A-1\"}",
                """
                [{"columnIndex":2,"header":"账户ID","cellType":"STRING","value":"A-1"},
                 {"columnIndex":18,"header":"计费区域","cellType":"BLANK","value":null}]
                """);

        var result = planner.plan("ARREARS", List.of(uploaded), List.of(old), 88);

        assertEquals(1, result.updatedRows());
        assertTrue(result.uploadedRows().get(0).changedFields().toString().contains("\"cleared\":true"));
        assertEquals(18, result.uploadedRows().get(0).effectiveRawFields().get(1).get("columnIndex").asInt());
        assertEquals(88, result.uploadedRows().get(0).effectiveProvenance()
                .get("计费区域#1").get("batchId").asLong());
    }

    private LedgerMergePlanner.Candidate candidate(String key, String normalized, String raw) throws Exception {
        return new LedgerMergePlanner.Candidate(101, key, "sheet", 2,
                mapper.readTree(normalized), mapper.readTree(raw));
    }

    private LedgerMergePlanner.Current current(String key, String normalized, String raw) throws Exception {
        return current(key, normalized, raw, mapper.createObjectNode());
    }

    private LedgerMergePlanner.Current current(String key, String normalized, String raw,
                                               JsonNode provenance) throws Exception {
        return new LedgerMergePlanner.Current(key, 1, 10, 11, 12, "sheet", 2, "张三", 5L,
                mapper.readTree(normalized), mapper.readTree(raw), provenance);
    }
}
