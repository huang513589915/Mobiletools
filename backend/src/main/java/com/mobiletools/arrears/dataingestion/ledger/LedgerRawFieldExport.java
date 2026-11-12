package com.mobiletools.arrears.dataingestion.ledger;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds stable export columns from effective raw fields, keyed by header and duplicate occurrence. */
final class LedgerRawFieldExport {
    private LedgerRawFieldExport() {}

    static List<Column> columns(List<JsonNode> rows) {
        Map<FieldKey, String> union = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            Map<String, Integer> occurrences = new LinkedHashMap<>();
            if (row == null || !row.isArray()) continue;
            for (JsonNode field : row) {
                if (!field.isObject() || !field.hasNonNull("header")) continue;
                String header = field.get("header").asText();
                int occurrence = occurrences.merge(header, 1, Integer::sum);
                union.putIfAbsent(new FieldKey(header, occurrence), header);
            }
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        union.keySet().forEach(key -> totals.merge(key.header(), 1, Integer::sum));
        List<Column> columns = new ArrayList<>(union.size());
        for (FieldKey key : union.keySet()) {
            String label = totals.get(key.header()) == 1
                    ? key.header() : key.header() + "（第" + key.occurrence() + "列）";
            columns.add(new Column(key, label));
        }
        return List.copyOf(columns);
    }

    static Map<FieldKey, String> values(JsonNode row) {
        Map<FieldKey, String> values = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        if (row == null || !row.isArray()) return values;
        for (JsonNode field : row) {
            if (!field.isObject() || !field.hasNonNull("header")) continue;
            String header = field.get("header").asText();
            int occurrence = occurrences.merge(header, 1, Integer::sum);
            values.put(new FieldKey(header, occurrence),
                    field.hasNonNull("value") ? field.get("value").asText() : "");
        }
        return values;
    }

    record Column(FieldKey key, String label) {}
    record FieldKey(String header, int occurrence) {}
}
