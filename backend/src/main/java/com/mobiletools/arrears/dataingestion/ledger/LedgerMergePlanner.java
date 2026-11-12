package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calculates an append-only row merge without mutating either workbook. */
public final class LedgerMergePlanner {
    private static final DateTimeFormatter BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final ObjectMapper mapper;

    public LedgerMergePlanner(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Plan plan(String sourceType, List<Candidate> candidates, List<Current> currentRows,
                     long candidateBatchId) {
        Map<String, Current> currentByIdentity = new LinkedHashMap<>();
        for (Current current : currentRows) currentByIdentity.put(current.identityKey(), current);
        Set<String> candidateIdentities = new LinkedHashSet<>();
        List<RowPlan> rows = new ArrayList<>();
        for (Candidate candidate : candidates) {
            candidateIdentities.add(candidate.identityKey());
            Current current = currentByIdentity.get(candidate.identityKey());
            JsonNode effectiveNormalized = overlayNormalized(current, candidate);
            ArrayNode effectiveRaw = overlayRaw(current, candidate);
            ObjectNode provenance = overlayProvenance(current, candidate, candidateBatchId);
            JsonNode changedFields = differences(current == null ? null : current.rawFields(), effectiveRaw,
                    current == null ? null : current.normalizedFields(), effectiveNormalized);
            String changeType = current == null ? "ADDED"
                    : changedFields.isEmpty() ? "UNCHANGED" : "UPDATED";
            rows.add(new RowPlan(changeType, candidate, current, effectiveNormalized,
                    effectiveRaw, provenance, changedFields));
        }
        List<Current> retained = currentRows.stream()
                .filter(row -> !candidateIdentities.contains(row.identityKey())).toList();
        return new Plan(rows, retained);
    }

    private JsonNode overlayNormalized(Current current, Candidate candidate) {
        ObjectNode result = current == null || current.normalizedFields() == null
                || !current.normalizedFields().isObject()
                ? mapper.createObjectNode() : ((ObjectNode) current.normalizedFields()).deepCopy();
        JsonNode uploaded = candidate.normalizedFields();
        if (uploaded != null && uploaded.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = uploaded.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                result.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        Set<String> presentHeaders = headers(candidate.rawFields());
        if (current != null) {
            Iterator<String> existing = result.fieldNames();
            List<String> existingNames = new ArrayList<>();
            existing.forEachRemaining(existingNames::add);
            for (String field : existingNames) {
                if (presentHeaders.contains(field) && (uploaded == null || !uploaded.has(field))) {
                    result.putNull(field);
                }
            }
        }
        return result;
    }

    private ArrayNode overlayRaw(Current current, Candidate candidate) {
        LinkedHashMap<FieldKey, ObjectNode> result = indexed(current == null ? null : current.rawFields());
        LinkedHashMap<FieldKey, ObjectNode> incoming = indexed(candidate.rawFields());
        for (Map.Entry<FieldKey, ObjectNode> entry : incoming.entrySet()) {
            result.put(entry.getKey(), entry.getValue().deepCopy());
        }
        ArrayNode array = mapper.createArrayNode();
        for (ObjectNode field : result.values()) {
            array.add(field);
        }
        return array;
    }

    private ObjectNode overlayProvenance(Current current, Candidate candidate, long batchId) {
        ObjectNode result = current == null || current.provenance() == null || !current.provenance().isObject()
                ? mapper.createObjectNode() : ((ObjectNode) current.provenance()).deepCopy();
        for (FieldKey key : indexed(candidate.rawFields()).keySet()) {
            ObjectNode source = mapper.createObjectNode();
            source.put("batchId", batchId);
            source.put("sheetName", candidate.sheetName());
            source.put("sourceRowNumber", candidate.sourceRowNumber());
            result.set(key.token(), source);
        }
        return result;
    }

    private JsonNode differences(JsonNode previousRaw, JsonNode nextRaw,
                                 JsonNode previousNormalized, JsonNode nextNormalized) {
        LinkedHashMap<FieldKey, ObjectNode> before = indexed(previousRaw);
        LinkedHashMap<FieldKey, ObjectNode> after = indexed(nextRaw);
        LinkedHashSet<FieldKey> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        ArrayNode changes = mapper.createArrayNode();
        for (FieldKey key : keys) {
            ObjectNode oldField = before.get(key);
            ObjectNode newField = after.get(key);
            String beforeValue = semanticValue(key, oldField, previousNormalized);
            String afterValue = semanticValue(key, newField, nextNormalized);
            if (java.util.Objects.equals(beforeValue, afterValue)) continue;
            ObjectNode change = mapper.createObjectNode();
            change.put("header", key.header());
            change.put("occurrence", key.occurrence());
            if (beforeValue == null) change.putNull("before"); else change.put("before", beforeValue);
            if (afterValue == null) change.putNull("after"); else change.put("after", afterValue);
            change.put("cleared", beforeValue != null && afterValue == null);
            changes.add(change);
        }
        return changes;
    }

    private String semanticValue(FieldKey key, ObjectNode raw, JsonNode normalized) {
        String header = key.header();
        if (key.occurrence() == 1 && normalized != null && normalized.isObject() && normalized.has(header)) {
            JsonNode value = normalized.get(header);
            if (value == null || value.isNull()) return null;
            if (value.isArray() && value.size() == 3 && ("计费截止日期".equals(header))) {
                return LocalDate.of(value.get(0).asInt(), value.get(1).asInt(), value.get(2).asInt())
                        .format(BASIC_DATE);
            }
            if (value.isNumber()) return value.decimalValue().stripTrailingZeros().toPlainString();
            return value.asText();
        }
        if (raw == null || !raw.hasNonNull("value")) return null;
        String value = raw.get("value").asText();
        if (value.isBlank()) return null;
        try {
            if (value.matches("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)")) {
                return new BigDecimal(value).stripTrailingZeros().toPlainString();
            }
        } catch (NumberFormatException ignored) {
            // Preserve non-numeric source strings.
        }
        return value;
    }

    private Set<String> headers(JsonNode rawFields) {
        Set<String> result = new LinkedHashSet<>();
        for (ObjectNode field : indexed(rawFields).values()) result.add(field.path("header").asText());
        return result;
    }

    private LinkedHashMap<FieldKey, ObjectNode> indexed(JsonNode fields) {
        LinkedHashMap<FieldKey, ObjectNode> result = new LinkedHashMap<>();
        if (fields == null || !fields.isArray()) return result;
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonNode value : fields) {
            if (!value.isObject() || !value.hasNonNull("header")) continue;
            ObjectNode field = ((ObjectNode) value).deepCopy();
            String header = field.path("header").asText();
            int occurrence = occurrences.merge(header, 1, Integer::sum);
            result.put(new FieldKey(header, occurrence), field);
        }
        return result;
    }

    public record Candidate(long importRowId, String identityKey, String sheetName, int sourceRowNumber,
                            JsonNode normalizedFields, JsonNode rawFields) {}

    public record Current(String identityKey, long effectiveRowId, long latestBatchId,
                          long snapshotRecordId, long importRowId, String sheetName,
                          int sourceRowNumber, String excelManagerName, Long effectiveManagerUserId,
                          JsonNode normalizedFields, JsonNode rawFields, JsonNode provenance) {}

    public record RowPlan(String changeType, Candidate candidate, Current current,
                          JsonNode effectiveNormalizedFields, JsonNode effectiveRawFields,
                          JsonNode effectiveProvenance, JsonNode changedFields) {}

    public record Plan(List<RowPlan> uploadedRows, List<Current> retainedRows) {
        public Plan {
            uploadedRows = List.copyOf(uploadedRows);
            retainedRows = List.copyOf(retainedRows);
        }
        public long count(String type) { return uploadedRows.stream().filter(row -> type.equals(row.changeType())).count(); }
        public int addedRows() { return Math.toIntExact(count("ADDED")); }
        public int updatedRows() { return Math.toIntExact(count("UPDATED")); }
        public int unchangedRows() { return Math.toIntExact(count("UNCHANGED")); }
        public int retainedCount() { return retainedRows.size(); }
        public boolean semanticNoOp() { return addedRows() == 0 && updatedRows() == 0; }
    }

    private record FieldKey(String header, int occurrence) {
        String token() { return header + "#" + occurrence; }
    }
}
