package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Records a failed confirmation only after its snapshot transaction has rolled back. */
@Service
public class LedgerBatchFailureRecorder {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LedgerBatchFailureRecorder(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long batchId, String failureReason) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT validation_summary, batch_status
                FROM excel_import_batch WHERE id=? FOR UPDATE
                """, batchId);
        if (rows.isEmpty() || "CONFIRMED".equals(rows.get(0).get("batch_status"))) return;
        JsonNode summary = parse(rows.get(0).get("validation_summary"));
        var object = summary.isObject() ? (com.fasterxml.jackson.databind.node.ObjectNode) summary
                : objectMapper.createObjectNode();
        object.put("confirmationFailure", limit(failureReason));
        try {
            jdbc.update("""
                    UPDATE excel_import_batch
                    SET batch_status='FAILED', error_rows=GREATEST(error_rows, 1), validation_summary=?
                    WHERE id=? AND batch_status<>'CONFIRMED'
                    """, objectMapper.writeValueAsString(object), batchId);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法记录导入批次失败原因", exception);
        }
    }

    private JsonNode parse(Object value) {
        if (value == null) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(String.valueOf(value));
        } catch (JsonProcessingException exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String limit(String value) {
        if (value == null || value.isBlank()) return "正式快照写入失败";
        String trimmed = value.strip();
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }
}
