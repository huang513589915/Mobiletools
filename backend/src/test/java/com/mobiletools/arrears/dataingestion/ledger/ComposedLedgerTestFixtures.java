package com.mobiletools.arrears.dataingestion.ledger;

import org.springframework.jdbc.core.JdbcTemplate;

/** Minimal H2 representation of the composed-current-dataset schema used by ledger queries. */
final class ComposedLedgerTestFixtures {
    private ComposedLedgerTestFixtures() {}

    static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE excel_dataset_period (source_type VARCHAR(20), statistics_key VARCHAR(8), current_version_id BIGINT, changed_by_user_id BIGINT, changed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(source_type, statistics_key))");
        jdbc.execute("CREATE TABLE excel_effective_row (id BIGINT PRIMARY KEY, source_type VARCHAR(20), statistics_key VARCHAR(8), identity_key VARCHAR(255), latest_batch_id BIGINT, snapshot_record_id BIGINT, raw_fields CLOB, normalized_fields CLOB, field_provenance CLOB)");
        jdbc.execute("CREATE TABLE excel_dataset_version_row (dataset_version_id BIGINT, source_type VARCHAR(20), statistics_key VARCHAR(8), identity_key VARCHAR(255), effective_row_id BIGINT, PRIMARY KEY(dataset_version_id, identity_key))");
        jdbc.execute("CREATE VIEW excel_current_dataset AS "
                + "SELECT p.source_type, p.statistics_key, p.current_version_id, e.latest_batch_id AS batch_id, "
                + "e.snapshot_record_id, e.id AS effective_row_id, m.identity_key, "
                + "e.raw_fields AS effective_raw_fields, e.normalized_fields AS effective_normalized_fields, "
                + "e.field_provenance, p.changed_by_user_id, p.changed_at "
                + "FROM excel_dataset_period p "
                + "JOIN excel_dataset_version_row m ON m.dataset_version_id=p.current_version_id "
                + "JOIN excel_effective_row e ON e.id=m.effective_row_id");
    }

    static void addPeriod(JdbcTemplate jdbc, String sourceType, String statisticsKey, long versionId) {
        jdbc.update("INSERT INTO excel_dataset_period(source_type, statistics_key, current_version_id) VALUES (?,?,?)",
                sourceType, statisticsKey, versionId);
    }

    static void addCurrentSnapshot(JdbcTemplate jdbc, long versionId, String sourceType,
                                   String statisticsKey, long snapshotRecordId) {
        SnapshotRow row = jdbc.queryForObject("""
                SELECT s.batch_id, r.identity_key, r.raw_fields, r.normalized_fields
                FROM excel_snapshot_record s
                JOIN excel_import_row r ON r.id=s.import_row_id AND r.batch_id=s.batch_id
                WHERE s.id=? AND s.source_type=?
                """, (rs, index) -> new SnapshotRow(rs.getLong("batch_id"), rs.getString("identity_key"),
                rs.getString("raw_fields"), rs.getString("normalized_fields")), snapshotRecordId, sourceType);
        addCurrentSnapshot(jdbc, versionId, sourceType, statisticsKey, snapshotRecordId,
                row.batchId(), row.identityKey(), row.rawFields(), row.normalizedFields());
    }

    static void addCurrentSnapshot(JdbcTemplate jdbc, long versionId, String sourceType,
                                   String statisticsKey, long snapshotRecordId, long batchId,
                                   String identityKey, String rawFields, String normalizedFields) {
        jdbc.update("""
                INSERT INTO excel_effective_row(
                    id, source_type, statistics_key, identity_key, latest_batch_id,
                    snapshot_record_id, raw_fields, normalized_fields, field_provenance
                ) VALUES (?,?,?,?,?,?,?,?, '{}')
                """, snapshotRecordId, sourceType, statisticsKey, identityKey, batchId,
                snapshotRecordId, rawFields == null ? "[]" : rawFields,
                normalizedFields == null ? "{}" : normalizedFields);
        jdbc.update("""
                INSERT INTO excel_dataset_version_row(
                    dataset_version_id, source_type, statistics_key, identity_key, effective_row_id
                ) VALUES (?,?,?,?,?)
                """, versionId, sourceType, statisticsKey, identityKey, snapshotRecordId);
    }

    private record SnapshotRow(long batchId, String identityKey, String rawFields, String normalizedFields) {}
}
